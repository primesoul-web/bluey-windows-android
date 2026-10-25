using System.Net;
using System.Net.Sockets;

namespace GooglyProtocol;

/// <summary>
/// Listens for phones on the local network and streams face updates to any that connect.
/// Port of <c>PhoneServer.swift</c> in Mac/Sources/GooglyMac — same handshake, same throttle,
/// same 2-second listener restart. The listener binds <see cref="DefaultPort"/> (a port
/// addition: the original used NWListener's ephemeral ports, which only worked because
/// Bonjour always resolved them; a fixed port is what makes Windows Firewall rules, USB
/// tethering and <c>adb reverse</c> possible) and reports the port it actually got through
/// <see cref="Started"/> so the mDNS advertiser (in the app) can (re)announce it.
/// </summary>
public sealed class PhoneServer : IDisposable
{
    /// <summary>The port phones connect on (port addition D12). If it's taken, the server
    /// falls back to an ephemeral port and mDNS still resolves it.</summary>
    public const int DefaultPort = 51826;
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

    /// <summary>Diagnostic sink (port addition): connections, disconnects, every command
    /// received and any undecodable line, so a broken request path can be traced in
    /// %APPDATA%\Googly\log.txt. May be invoked from socket threads.</summary>
    public Action<string>? Log { get; set; }

    public int Port { get; private set; }

    public PhoneServer(string? hostName = null, int port = DefaultPort)
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
        var port = _requestedPort;
        var fellBack = false;
        while (!ct.IsCancellationRequested)
        {
            TcpListener? listener = null;
            var retryImmediately = false;
            try
            {
                listener = new TcpListener(IPAddress.Any, port);
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
                // The fixed port is taken (another Bluey, or something squatting on it) —
                // fall back to an ephemeral port once so the app still comes up over mDNS;
                // otherwise retry the same port in 2 seconds, like the original.
                if (port != 0 && !fellBack)
                {
                    fellBack = true;
                    port = 0;
                    retryImmediately = true;
                }
            }
            finally
            {
                try { listener?.Stop(); } catch { /* already gone */ }
            }

            if (ct.IsCancellationRequested) break;
            if (retryImmediately) continue;
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

        try { Log?.Invoke($"phone #{id} connected from {tcp.Client.RemoteEndPoint}"); }
        catch { /* diagnostics must never break the server */ }

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
            var framer = new LineFramer
            {
                OnInvalidLine = line => Log?.Invoke($"phone #{id} sent an undecodable line ({line.Length} chars): " +
                    (line.Length <= 120 ? line : line[..120] + "…")),
            };
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
            try { Log?.Invoke($"phone #{id} disconnected"); }
            catch { /* diagnostics must never break the server */ }
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
            return; // a hello carries nothing else in practice
        }

        if (packet.Command is not null)
        {
            // Diagnostic trace of every command a phone sends (port addition): the
            // face stream never carries a command, so this stays low-volume. The text
            // preview catches the phone's own error captions (e.g. its give-up message)
            // arriving here — proof the phone-to-PC direction works at that moment.
            // Tool names join the trace since v0.3.6 (the first hardware session's
            // look_at_screen call logged as a bare text="{}" otherwise).
            var tool = packet.Tool is { Length: > 0 } n ? " tool=" + n : "";
            var preview = packet.Text is { Length: > 0 } t ? " text=\"" + (t.Length <= 60 ? t : t[..60] + "…") + "\"" : "";
            try { Log?.Invoke($"phone #{client.Id} -> {packet.Command} callID={packet.CallID ?? "-"}{tool}{preview}"); }
            catch { /* diagnostics must never break the server */ }
            try { RequestReceived?.Invoke(packet, reply => SendTo(client, reply)); }
            catch { /* handler errors must not kill the connection */ }
            return;
        }

        if (packet.Face is not null) return; // the face stream never logs (volume)

        // Forensic completeness (v0.3.3): anything else a phone sends — a reply-shaped
        // packet, a callID with no command, a stray caption — is logged, never silently
        // dropped. The month-long "the token request vanished" hunt was blind to exactly
        // this class of packet: if it doesn't carry a Command it was invisible.
        try { Log?.Invoke($"phone #{client.Id} sent a packet with no command ({Describe(packet)}) — ignored"); }
        catch { /* diagnostics must never break the server */ }
    }

    /// <summary>The set fields of an odd packet, for the forensic log line.</summary>
    private static string Describe(Packet p)
    {
        var shape = new List<string>();
        if (p.Hello is not null) shape.Add("hello"); // unreachable here; kept for safety
        if (p.CallID is not null) shape.Add("callID");
        if (p.Tool is not null) shape.Add("tool");
        if (p.Text is not null) shape.Add($"text({p.Text.Length})");
        if (p.Audio is not null) shape.Add("audio");
        if (p.Image is not null) shape.Add("image");
        if (p.Model is not null) shape.Add("model");
        if (p.Error is not null) shape.Add("error");
        if (p.Speech is not null) shape.Add("speech");
        if (p.Volume is not null) shape.Add("volume");
        return shape.Count == 0 ? "empty" : string.Join("+", shape);
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
