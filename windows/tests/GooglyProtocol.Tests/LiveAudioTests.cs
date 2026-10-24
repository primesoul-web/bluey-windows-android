using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// LiveAudio chunking (v0.3.4): the self-test's stand-in audio must match the phone's
/// mic stream exactly — 150 ms PCM16 frames, base64 per frame, partial tail allowed.
/// </summary>
public class LiveAudioTests
{
    [Fact]
    public void Frames_are_the_phones_exact_chunk_size()
    {
        var pcm = new byte[LiveAudio.FrameBytes * 3];
        var frames = LiveAudio.Base64Frames(pcm);
        Assert.Equal(3, frames.Length);
        Assert.All(frames, f => Assert.Equal(LiveAudio.FrameBytes, Convert.FromBase64String(f).Length));
    }

    [Fact]
    public void The_last_frame_may_be_short()
    {
        var pcm = new byte[LiveAudio.FrameBytes + 123];
        var frames = LiveAudio.Base64Frames(pcm);
        Assert.Equal(2, frames.Length);
        Assert.Equal(LiveAudio.FrameBytes, Convert.FromBase64String(frames[0]).Length);
        Assert.Equal(123, Convert.FromBase64String(frames[1]).Length);
    }

    [Fact]
    public void Content_round_trips_through_the_base64_frames()
    {
        var pcm = new byte[LiveAudio.FrameBytes + 77];
        new Random(42).NextBytes(pcm);
        var rejoined = LiveAudio.Base64Frames(pcm)
            .SelectMany(Convert.FromBase64String)
            .ToArray();
        Assert.Equal(pcm, rejoined);
    }

    [Fact]
    public void Empty_audio_makes_no_frames()
    {
        Assert.Empty(LiveAudio.Base64Frames(Array.Empty<byte>()));
    }
}
