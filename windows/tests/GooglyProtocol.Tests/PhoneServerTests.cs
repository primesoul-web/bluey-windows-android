using System.Net.Sockets;
using System.Text;
using GooglyProtocol;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// End-to-end tests over real localhost TCP sockets: handshake, phone registry,
/// face throttling, request/reply correlation, and reconnect face re-send —
/// the same behaviors PhoneServer.swift drives on the Mac.
/// </summary>
public class PhoneServerTests
{
    /// <summary>A test phone: connects, speaks NDJSON, collects packets.</summary>
    private sealed class TestPhone : IDisposable
    {
        private readonly TcpClient _tcp = new();
        private readonly Task _readLoop;
        private readonly LineFramer _framer = new();
        private readonly CancellationTokenSource _cts = new();
        public readonly List<Packet> Received = new();
        public readonly SemaphoreSlim Signal = new(0, 100);

        public TestPhone(int port)
        {
            _tcp.Connect("127.0.0.1", port);
            var stream = _tcp.GetStream();
            _readLoop = Task.Run(async () =>
            {
                var buffer = new byte[64 * 1024];
                try
                {
                    while (!_cts.IsCancellationRequested)
                    {
                        var n = await stream.ReadAsync(buffer, _cts.Token);
                        if (n <= 0) break;
                        lock (Received)
                        {
                            Received.AddRange(_framer.Feed(buffer.AsSpan(0, n)));
                            if (Received.Count > 0) Signal.Release(Received.Count);
                        }
                    }
                }
                catch { /* closed */ }
            });
        }

        public void Send(Packet packet) => SendRaw(PacketCodec.Encode(packet));

        public void SendRaw(byte[] bytes)
        {
            var stream = _tcp.GetStream();
            lock (_tcp)
            {
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush();
            }
        }

        public async Task<Packet> NextAsync(TimeSpan? timeout = null)
        {
            var deadline = DateTime.UtcNow + (timeout ?? TimeSpan.FromSeconds(5));
            while (DateTime.UtcNow < deadline)
            {
                lock (Received)
                {
                    if (Received.Count > 0)
                    {
                        var packet = Received[0];
                        Received.RemoveAt(0);
                        return packet;
                    }
                }
                await Signal.WaitAsync(TimeSpan.FromMilliseconds(50));
            }
            throw new TimeoutException("no packet arrived");
        }

        public void Dispose()
        {
            _cts.Cancel();
            _tcp.Close();
        }
    }

    private sealed class ServerHarness : IDisposable
    {
        public readonly PhoneServer Server = new("Test PC");
        public readonly TaskCompletionSource<int> Started = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public readonly List<string> PhoneEvents = new();

        public ServerHarness()
        {
            Server.Started += port => { try { Started.SetResult(port); } catch { } };
            Server.PhonesChanged += names => { lock (PhoneEvents) PhoneEvents.Add(string.Join(",", names)); };
            Server.Start();
        }

        public void Dispose() => Server.Dispose();
    }

    private static async Task<int> StartServerAsync()
    {
        using var harness = new ServerHarness();
        return await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
    }

    [Fact]
    public async Task Server_greets_with_hello_and_repeats_the_last_face()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        harness.Server.Send(FaceState.Create(0.25, -0.5, Mood.happy, 0.1));

        using var phone = new TestPhone(port);
        var hello = await phone.NextAsync();
        Assert.Equal("Test PC", hello.Hello);

        // The face sent before any phone connected still reaches a late joiner…
        var facePacket = await phone.NextAsync();
        Assert.NotNull(facePacket.Face);
        Assert.Equal(Mood.happy, facePacket.Face!.Mood);

        // …and a reconnecting phone gets hello + the current face again.
        using var phone2 = new TestPhone(port);
        Assert.Equal("Test PC", (await phone2.NextAsync()).Hello);
        var face2 = await phone2.NextAsync();
        Assert.Equal(0.25, face2.Face!.GazeX);
    }

    [Fact]
    public async Task Phone_hello_updates_the_phone_list()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));

        using var phone = new TestPhone(port);
        await phone.NextAsync(); // server hello
        phone.Send(new Packet { Hello = "Pixel 9" });

        await WaitUntilAsync(() =>
        {
            var names = harness.Server.PhoneNames;
            return names.Count == 1 && names[0] == "Pixel 9";
        });
    }

    [Fact]
    public async Task Unknown_phone_shows_as_Phone_until_it_says_hello()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        using var phone = new TestPhone(port);
        await WaitUntilAsync(() => harness.Server.PhoneNames.Count == 1);
        Assert.Equal("Phone", harness.Server.PhoneNames[0]);
    }

    [Fact]
    public async Task Face_updates_are_throttled_to_at_least_one_per_second()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        using var phone = new TestPhone(port);
        await phone.NextAsync(); // hello (no face yet — none was ever sent)

        var face = FaceState.Create(0, 0, Mood.listening, 0);
        for (var i = 0; i < 60; i++)
        {
            harness.Server.Send(face);
            await Task.Delay(20);
        }

        // 1.2 s of identical faces → exactly one delivery (t=0) plus the ≥1 s resend (t≈1.0)
        var first = await phone.NextAsync();
        Assert.NotNull(first.Face);
        var second = await phone.NextAsync(TimeSpan.FromSeconds(3));
        Assert.NotNull(second.Face);

        lock (phone.Received) Assert.Empty(phone.Received); // nothing else queued
    }

    [Fact]
    public async Task Different_faces_are_not_throttled()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        using var phone = new TestPhone(port);
        await phone.NextAsync();

        harness.Server.Send(FaceState.Create(0, 0, Mood.listening, 0));
        harness.Server.Send(FaceState.Create(0.5, 0, Mood.listening, 0));   // gaze moved
        harness.Server.Send(FaceState.Create(0.5, 0, Mood.thinking, 0));    // mood changed

        var a = await phone.NextAsync();
        var b = await phone.NextAsync();
        var c = await phone.NextAsync();
        Assert.Equal(0, a.Face!.GazeX);
        Assert.Equal(0.5, b.Face!.GazeX);
        Assert.Equal(Mood.thinking, c.Face!.Mood);
    }

    [Fact]
    public async Task Tool_requests_get_replies_with_the_same_callID()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));

        var requests = new List<Packet>();
        harness.Server.RequestReceived += (packet, reply) =>
        {
            lock (requests) requests.Add(packet);
            reply(new Packet { Command = "toolResult", CallID = packet.CallID, Text = "Frontmost app: Chrome" });
        };

        using var phone = new TestPhone(port);
        await phone.NextAsync();
        phone.Send(new Packet { Command = "tool", CallID = "ABC-123", Tool = "look_at_screen", Text = "{}" });

        var reply = await phone.NextAsync();
        Assert.Equal("toolResult", reply.Command);
        Assert.Equal("ABC-123", reply.CallID);
        Assert.Equal("Frontmost app: Chrome", reply.Text);
        lock (requests) Assert.Single(requests);
    }

    [Fact]
    public async Task Broadcast_reaches_every_phone()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        using var phoneA = new TestPhone(port);
        using var phoneB = new TestPhone(port);
        await phoneA.NextAsync();
        await phoneB.NextAsync();

        harness.Server.Broadcast(new Packet { Command = "wake" });

        Assert.Equal("wake", (await phoneA.NextAsync()).Command);
        Assert.Equal("wake", (await phoneB.NextAsync()).Command);
    }

    [Fact]
    public async Task Disconnecting_phones_disappear_from_the_list()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var phone = new TestPhone(port);
        await phone.NextAsync();
        phone.Send(new Packet { Hello = "Pixel 9" });
        await WaitUntilAsync(() => harness.Server.PhoneNames.Contains("Pixel 9"));

        phone.Dispose();
        await WaitUntilAsync(() => harness.Server.PhoneNames.Count == 0);
    }

    [Fact]
    public async Task Garbage_lines_do_not_kill_the_connection()
    {
        using var harness = new ServerHarness();
        var port = await harness.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var seenCommands = new List<string>();
        harness.Server.RequestReceived += (packet, _) =>
        {
            lock (seenCommands) seenCommands.Add(packet.Command!);
        };

        using var phone = new TestPhone(port);
        await phone.NextAsync();

        phone.SendRaw(Encoding.UTF8.GetBytes("this is not json\n"));
        phone.SendRaw(Encoding.UTF8.GetBytes("{\"command\":42}\n"));
        phone.Send(new Packet { Command = "awake" });

        // The connection is still alive: the server saw the awake command…
        await WaitUntilAsync(() => { lock (seenCommands) return seenCommands.Contains("awake"); });

        // …and it can still send the other way.
        harness.Server.Broadcast(new Packet { Command = "wake" });
        Assert.Equal("wake", (await phone.NextAsync()).Command);
    }

    [Fact]
    public async Task Server_binds_an_ephemeral_port_and_reports_it()
    {
        var port = await StartServerAsync();
        Assert.NotEqual(0, port);
    }

    private static async Task WaitUntilAsync(Func<bool> condition, TimeSpan? timeout = null)
    {
        var deadline = DateTime.UtcNow + (timeout ?? TimeSpan.FromSeconds(5));
        while (DateTime.UtcNow < deadline)
        {
            if (condition()) return;
            await Task.Delay(25);
        }
        Assert.Fail("condition never became true");
    }
}
