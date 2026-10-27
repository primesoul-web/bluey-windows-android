using GooglyRealtime;
using Xunit;

namespace GooglyProtocol.Tests;

/// <summary>
/// The tray-toggle semantics (v0.4.1). The v0.4.0 hardware report: Eyes Follow My
/// Mouse, Cursor Glow and Show Cursor could never be changed from the tray menu.
/// WPF flips a checkable item's IsChecked BEFORE the Click event fires, so the old
/// handlers — which negated the value they read — were sending the OLD value back.
/// These tests pin the contract the tray now relies on: <see cref="TrayToggle.Click"/>
/// returns the NEW value, the applied value and the rebuild checkmark agree, and a
/// settings sync overrides whatever the last click left behind.
/// </summary>
public class TrayToggleTests
{
    [Fact]
    public void Click_returns_the_new_value_and_flips_the_state()
    {
        var t = new TrayToggle(true);
        Assert.False(t.Click()); // on → off: the handler must apply false
        Assert.False(t.IsOn);
        Assert.True(t.Click()); // off → on: the handler must apply true
        Assert.True(t.IsOn);
    }

    [Fact]
    public void Clicking_from_off_applies_true_on_the_first_try()
    {
        // The exact v0.4.0 failure shape: an off toggle must turn ON when clicked.
        var t = new TrayToggle(false);
        Assert.True(t.Click());
        Assert.True(t.IsOn);
    }

    [Fact]
    public void The_menu_rebuild_agrees_with_what_was_applied()
    {
        // ShowMenu reads its checkmarks from IsOn; the handler applied what Click()
        // returned. After any number of clicks the two must never disagree — that
        // disagreement was the "the checkmark never moves" symptom.
        var t = new TrayToggle(true);
        var applied = new List<bool>();
        for (var i = 0; i < 6; i++) applied.Add(t.Click());
        var shownOnRebuild = new List<bool>();
        for (var i = 0; i < 6; i++) shownOnRebuild.Add(t.IsOn); // "rebuild" reads the mark
        Assert.Equal(applied[^1], shownOnRebuild[^1]);
        // And the sequence alternates, so every click really changed something.
        Assert.Equal(new[] { false, true, false, true, false, true }, applied);
    }

    [Fact]
    public void Sync_overrides_the_click_history_like_a_settings_change()
    {
        // Settings are the truth: whatever the user clicked last, Sync forces the
        // value the settings file has, and the next Click flips from there.
        var t = new TrayToggle(false);
        Assert.True(t.Click()); // now on
        t.Sync(false); // settings say off (e.g. edited elsewhere / first sync)
        Assert.False(t.IsOn); // the rebuilt menu shows off
        Assert.True(t.Click()); // and the next click applies true again
    }

    [Fact]
    public void Sync_is_idempotent()
    {
        var t = new TrayToggle(true);
        t.Sync(true);
        t.Sync(true);
        Assert.True(t.IsOn);
        Assert.False(t.Click());
        t.Sync(false);
        t.Sync(false);
        Assert.False(t.IsOn);
    }
}
