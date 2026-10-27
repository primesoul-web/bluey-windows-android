namespace GooglyRealtime;

/// <summary>
/// The semantics of one checkable tray-menu item, extracted so they can be unit-tested.
/// The v0.4.0 tray bug: WPF flips a checkable item's <c>IsChecked</c> BEFORE the
/// <c>Click</c> event fires, so a handler that negates what it reads sends the OLD
/// value back — Eyes Follow My Mouse, Cursor Glow and Show Cursor could never change.
/// The tray now keeps one of these per toggle, rebuilds the menu's checkmarks from it,
/// and applies exactly the value <see cref="Click"/> returns (never a negation).
/// </summary>
public sealed class TrayToggle
{
    /// <summary>The current value — what the menu's checkmark shows on rebuild.</summary>
    public bool IsOn { get; private set; }

    public TrayToggle(bool initial) => IsOn = initial;

    /// <summary>The item was clicked: flips and returns the NEW value — the one to
    /// apply. (Matches what WPF leaves in <c>MenuItem.IsChecked</c> by the time the
    /// Click handler runs, because WPF has already done the same flip.)</summary>
    public bool Click()
    {
        IsOn = !IsOn;
        return IsOn;
    }

    /// <summary>Forces the value. The settings file is the truth; the tray's copy
    /// only exists so the menu can rebuild its checkmarks before the (async)
    /// settings-changed sync lands.</summary>
    public void Sync(bool value) => IsOn = value;
}
