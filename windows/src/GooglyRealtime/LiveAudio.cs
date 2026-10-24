namespace GooglyRealtime;

/// <summary>
/// Live-audio input helpers shared by the self-test (and mirroring the phone's mic
/// stream exactly): 16 kHz PCM16 mono, 150 ms per chunk, base64 per frame.
/// </summary>
public static class LiveAudio
{
    /// <summary>150 ms of PCM16 mono at 16 kHz — the phone's MicStreamer reads 2400
    /// samples (4800 bytes) per chunk, so the self-test's stand-in audio must too.</summary>
    public const int FrameBytes = 4800;

    /// <summary>Splits raw PCM16 into the phone's exact chunk size, base64-encoded
    /// per frame, ready to wrap in <c>realtimeInput.audio</c> envelopes. The last
    /// frame may be shorter — so is the phone's, when the mic stops mid-chunk.</summary>
    public static string[] Base64Frames(byte[] pcm16)
    {
        if (pcm16.Length == 0) return Array.Empty<string>();
        var frames = new List<string>(checked(pcm16.Length / FrameBytes) + 1);
        for (var i = 0; i < pcm16.Length; i += FrameBytes)
        {
            var count = Math.Min(FrameBytes, pcm16.Length - i);
            frames.Add(Convert.ToBase64String(pcm16, i, count));
        }
        return frames.ToArray();
    }
}
