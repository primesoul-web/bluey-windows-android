using System.Net;
using System.Net.Sockets;

namespace GooglyProtocol;

/// <summary>
/// Listens for phones on the local network and streams face updates to any that connect.
/// Port of <c>PhoneServer.swift</c> in Mac/Sources/GooglyMac — same handshake, same throttle,
/// same 2-second listener restart. The listener binds an ephemeral port (like NWListener)
/// and reports it through <see cref="Started"/> so the mDNS advertiser (in the app) can
/// (re)announce it.
/// </summary>
public sealed class PhoneServer : IDisposable
{
    private readonly string _hostName;
    private readonly int _requestedPort;
    private readonly Dictionary<long, Client> _clients = new();
    private long _nextClientId;
    private FaceState? _lastFace;
    private readonly FaceThrottle _throttle = new();
    private readonly CancellationTokenSource _cts = new();
    private Task? _runTask;
    private readonly object _gate = new();
    private volatile bool _disposed;

    /// <summary>Raised with the names of connected phones whenever that list changes. May fire on any thread.</summary>
    public event Action<IReadOnlyList<string>>? PhonesChanged;

    /// <summary>Raised when a phone asks for something. The second argument replies to that phone. May fire on any thread.</summary>
    public event Action<Packet, Action<Packet>>? RequestReceived;

    /// <summary>Raised every time the listener (re)starts, with the port it bound.</summary>
    public event Action<int>? Started;

    public int Port { get; private set; }

    public PhoneServer(string? hostName = null, int port = 0)
    {
        _hostName = string.IsNullOrWhiteSpace(hostName) ? "PC" : hostName;
        _requestedPort = port;
    }

    /// <summary>The connected phone names (unknown phones show as "Phone").</summary>
    public IReadOnlyList<string> PhoneNames
    {
        get
        {
            lock (_gate)
            {
                return _clients.Values.Select(c => c.Name ?? "Phone").ToList();
            }
        }
    }

    public void Start()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        _runTask = Task.Run(() => RunAsync(_cts.Token));
    }

    private async Task RunAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpListener? listener = null;
            try
            {
                listener = new TcpListener(IPAddress.Any, _requestedPort);
                listener.Start();
                Port = ((IPEndPoint)listener.Server.LocalEndPoint!).Port;
                try { Started?.Invoke(Port); } catch { /* listeners must not break the server */ }

                while (!ct.IsCancellationRequested)
                {
                    var tcp = await listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                    _ = Task.Run(() => AcceptAsync(ct, tcp), CancellationToken.None);
                }
            }
            catch (Exception) when (!ct.IsCancellationRequested)
            {
                // listener failed — try again in 2 seconds, like the original
            }
            finally
            {
                try { listener?.Stop(); } catch { /* already gone */ }
            }

            if (ct.IsCancellationRequested) break;
            try { await Task.Delay(2000, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }
        }
    }

    private async Task AcceptAsync(CancellationToken ct, TcpClient tcp)
    {
        long id;
        Client client;
        try
        {
            tcp.NoDelay = true;
            if (OperatingSystem.IsWindows())
            {
                tcp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
                tcp.Client.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveTime, 2000);
            }
        }
        catch (SocketException)
        {
            // options are best-effort; a phone on plain TCP still works
        }

        lock (_gate)
        {
            id = ++_nextClientId;
            client = new Client(id, tcp);
            _clients[id] = client;
        }

        try
        {
            // Handshake: the PC greets right when the socket is ready, then re-sends the
            // current face so a reconnecting phone picks up where it left off.
            SendTo(client, new Packet { Hello = _hostName });
            FaceState? lastFace;
            lock (_gate) lastFace = _lastFace;
            if (lastFace is not null)
                SendTo(client, new Packet { Face = lastFace });
        }
        catch (Exception)
        {
            Remove(client);
            return;
        }

        RaisePhonesChanged();

        try
        {
            var stream = tcp.GetStream();
            var framer = new LineFramer();
            var buffer = new byte[1024 * 1024]; // 1 MiB reads, like the original
            while (!ct.IsCancellationRequested)
            {
                int read;
                try
                {
                    read = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), ct).ConfigureAwait(false);
                }
                catch (Exception) when (!ct.IsCancellationRequested)
                {
                    break;
                }
                if (read <= 0) break;

                foreach (var packet in framer.Feed(buffer.AsSpan(0, read)))
                    HandlePacket(client, packet);
            }
        }
        catch (Exception)
        {
            // connection died mid-read — fall through to cleanup
        }
        finally
        {
            Remove(client);
        }
    }

    private void HandlePacket(Client client, Packet packet)
    {
        if (packet.Hello is { } name)
        {
            lock (_gate)
            {
                if (_clients.TryGetValue(client.Id, out var live))
                    live.Name = name;
            }
            RaisePhonesChanged();
        }

        if (packet.Command is not null)
        {
            try { RequestReceived?.Invoke(packet, reply => SendTo(client, reply)); }
            catch { /* handler errors must not kill the connection */ }
        }
    }

    /// <summary>Sends a command to every phone (e.g. "wake", "sleep").</summary>
    public void Broadcast(Packet packet)
    {
        Client[] snapshot;
        lock (_gate) snapshot = _clients.Values.ToArray();
        foreach (var client in snapshot)
        {
            try { SendTo(client, packet); } catch { /* dead phones are cleaned up by their read loop */ }
        }
    }

    /// <summary>Sends the face to every phone, skipping updates too small to see.</summary>
    public void Send(FaceState face)
    {
        lock (_gate)
        {
            if (!_throttle.ShouldSend(face, DateTimeOffset.UtcNow))
                return;
            _lastFace = face;
        }
        Broadcast(new Packet { Face = face });
    }

    private static void SendTo(Client client, Packet packet)
    {
        var line = PacketCodec.Encode(packet);
        lock (client.WriteLock)
        {
            var stream = client.Tcp.GetStream();
            stream.Write(line, 0, line.Length);
            stream.Flush();
        }
    }

    private void Remove(Client client)
    {
        lock (_gate)
        {
            if (!_clients.Remove(client.Id))
                return;
        }
        try { client.Tcp.Close(); } catch { /* already closed */ }
        RaisePhonesChanged();
    }

    private void RaisePhonesChanged()
    {
        var names = PhoneNames;
        try { PhonesChanged?.Invoke(names); }
        catch { /* listener errors must not kill the server */ }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _cts.Cancel();
        Client[] snapshot;
        lock (_gate) snapshot = _clients.Values.ToArray();
        foreach (var client in snapshot)
        {
            try { client.Tcp.Close(); } catch { /* already closed */ }
        }
        try { _runTask?.Wait(TimeSpan.FromSeconds(2)); } catch { /* shutdown races are fine */ }
        _cts.Dispose();
    }

    private sealed class Client(long id, TcpClient tcp)
    {
        public long Id { get; } = id;
        public TcpClient Tcp { get; } = tcp;
        public string? Name { get; set; }
        public object WriteLock { get; } = new();
    }
}
