namespace GooglyProtocol;

/// <summary>
/// Splits a TCP byte stream into newline-terminated packet lines, dropping any line
/// that doesn't decode — exactly the loop inside <c>LineConnection.receive</c> in
/// Shared/GooglyLink.swift. Partial lines are buffered until their newline arrives.
/// </summary>
public sealed class LineFramer
{
    private readonly List<byte> _pending = new();

    /// <summary>Diagnostic hook (port addition): invoked with every complete line that
    /// failed to decode, so the server log can show what a peer sent that wasn't a
    /// valid packet. Never set by tests; optional in production.</summary>
    public Action<string>? OnInvalidLine { get; set; }

    /// <summary>Feeds a chunk of bytes; returns every complete, valid packet it completes.</summary>
    public IReadOnlyList<Packet> Feed(ReadOnlySpan<byte> chunk)
    {
        byte[] buffer;
        if (_pending.Count == 0)
        {
            buffer = chunk.ToArray();
        }
        else
        {
            buffer = new byte[_pending.Count + chunk.Length];
            _pending.CopyTo(buffer, 0);
            chunk.CopyTo(buffer.AsSpan(_pending.Count));
            _pending.Clear();
        }

        List<Packet>? packets = null;
        var start = 0;
        for (var i = 0; i < buffer.Length; i++)
        {
            if (buffer[i] != (byte)'\n') continue;
            var line = buffer.AsSpan(start, i - start);
            start = i + 1;
            var packet = PacketCodec.TryDecode(line);
            if (packet is not null)
                (packets ??= new List<Packet>()).Add(packet);
            else if (line.Length > 0)
                OnInvalidLine?.Invoke(System.Text.Encoding.UTF8.GetString(line));
        }

        if (start < buffer.Length)
        {
            _pending.AddRange(buffer.AsSpan(start));
        }

        return (IReadOnlyList<Packet>?)packets ?? Array.Empty<Packet>();
    }
}
