using System.Windows;
using System.Windows.Controls;
using GooglyRealtime;

namespace GooglyWindows;

/// <summary>
/// The OpenAI API key sheet — port of <c>AppDelegate.editKeys</c>: a secure field, the
/// reassuring note, Save/Cancel. D10: "this Mac" → "this PC".
/// </summary>
public sealed class KeyWindow : Window
{
    public KeyWindow(OpenAiKeyStore keys)
    {
        Title = "OpenAI API Key";
        Width = 420;
        SizeToContent = SizeToContent.Height;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        ShowInTaskbar = false;
        ResizeMode = ResizeMode.NoResize;

        var saved = keys.Get("openai");
        var field = new PasswordBox { Height = 28 };
        var hint = new TextBlock
        {
            Text = saved is null ? "sk-…" : "Key saved. Paste a new one to replace it.",
            Foreground = System.Windows.Media.Brushes.Gray,
            VerticalAlignment = VerticalAlignment.Center,
            IsHitTestVisible = false,
            Margin = new Thickness(6, 0, 0, 0),
        };
        field.PasswordChanged += (_, _) => hint.Visibility =
            field.Password.Length == 0 ? Visibility.Visible : Visibility.Hidden;
        var fieldGrid = new Grid();
        fieldGrid.Children.Add(field);
        fieldGrid.Children.Add(hint);

        var cancel = new Button { Content = "Cancel", MinWidth = 88, Height = 30 };
        cancel.Click += (_, _) => DialogResult = false;
        var save = new Button { Content = "Save", MinWidth = 88, Height = 30, IsDefault = true };
        save.Click += (_, _) =>
        {
            var key = field.Password.Trim();
            if (key.Length > 0) keys.Set("openai", key);
            DialogResult = true;
        };

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        buttons.Children.Add(cancel);
        buttons.Children.Add(save);

        var root = new StackPanel { Margin = new Thickness(22) };
        root.Children.Add(new TextBlock
        {
            Text = "OpenAI API Key",
            FontSize = 15,
            FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(0, 0, 0, 6),
        });
        root.Children.Add(new TextBlock
        {
            Text = "Saved privately on this PC (not in the project files). The phone only ever gets short-lived keys.",
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.75,
            Margin = new Thickness(0, 0, 0, 14),
        });
        root.Children.Add(fieldGrid);
        root.Children.Add(buttons);

        Content = root;
        Loaded += (_, _) => field.Focus();
    }
}

/// <summary>
/// The personality sheet — port of <c>AppDelegate.editPersonality</c>: a scrollable
/// description of who he is, Save / Cancel / Reset to Default. Saving while he's awake
/// re-mints the session (the caller does sleep→wake).
/// </summary>
public sealed class PersonalityWindow : Window
{
    private readonly TextBox _text = new()
    {
        AcceptsReturn = true,
        TextWrapping = TextWrapping.Wrap,
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        Height = 220,
    };

    /// <summary>Result: null = cancelled, "" = reset to default, otherwise the new text.</summary>
    public string? Result { get; private set; }

    public PersonalityWindow(string current)
    {
        Title = "His Personality";
        Width = 528;
        SizeToContent = SizeToContent.Height;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        ShowInTaskbar = false;
        ResizeMode = ResizeMode.NoResize;

        _text.Text = current;

        var cancel = new Button { Content = "Cancel", MinWidth = 88, Height = 30 };
        cancel.Click += (_, _) => { Result = null; DialogResult = false; };
        var reset = new Button { Content = "Reset to Default", MinWidth = 110, Height = 30 };
        reset.Click += (_, _) => { Result = ""; DialogResult = true; };
        var save = new Button { Content = "Save", MinWidth = 88, Height = 30, IsDefault = true };
        save.Click += (_, _) => { Result = _text.Text; DialogResult = true; };

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        buttons.Children.Add(cancel);
        buttons.Children.Add(reset);
        buttons.Children.Add(save);

        var root = new StackPanel { Margin = new Thickness(22) };
        root.Children.Add(new TextBlock
        {
            Text = "His Personality",
            FontSize = 15,
            FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(0, 0, 0, 6),
        });
        root.Children.Add(new TextBlock
        {
            Text = "Describe who he is and how he talks. How he looks at and points at your screen stays built in.",
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.75,
            Margin = new Thickness(0, 0, 0, 14),
        });
        root.Children.Add(_text);
        root.Children.Add(buttons);

        Content = root;
        Loaded += (_, _) => _text.Focus();
    }
}
