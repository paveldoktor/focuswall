namespace FocusWall.Desktop;

/// <summary>Полноэкранный экран блокировки поверх всех окон. Закрыть его может только сама программа.</summary>
public sealed class LockForm : Form
{
    readonly Label _left;
    bool _allowClose;

    public LockForm(Screen screen)
    {
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        Bounds = screen.Bounds;
        TopMost = true;
        ShowInTaskbar = false;
        BackColor = Color.FromArgb(0x15, 0x19, 0x18);
        ForeColor = Color.FromArgb(0xE8, 0xEC, 0xE9);
        KeyPreview = true;

        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 5,
            BackColor = BackColor,
        };
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 50));

        var icon = new PictureBox
        {
            Image = Program.AppIcon.ToBitmap(),
            SizeMode = PictureBoxSizeMode.Zoom,
            Size = new Size(96, 96),
            Anchor = AnchorStyles.None,
        };
        var title = new Label
        {
            Text = "Время отдыхать",
            Font = new Font("Segoe UI Semibold", 40),
            AutoSize = true,
            Anchor = AnchorStyles.None,
            Padding = new Padding(0, 20, 0, 10),
        };
        _left = new Label
        {
            Font = new Font("Segoe UI", 16),
            ForeColor = Color.FromArgb(0x9A, 0xA5, 0xA0),
            AutoSize = true,
            Anchor = AnchorStyles.None,
        };
        panel.Controls.Add(icon, 0, 1);
        panel.Controls.Add(title, 0, 2);
        panel.Controls.Add(_left, 0, 3);
        Controls.Add(panel);
    }

    public void SetUntil(DateTime? until)
    {
        _left.Text = until == null
            ? "Компьютер заблокирован FocusWall."
            : $"Компьютер заблокирован FocusWall до {until:HH:mm} (ещё {Rules.FormatLeft(until.Value - DateTime.Now)}).";
    }

    public void ForceClose()
    {
        _allowClose = true;
        Close();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Alt+F4 и закрытие из панели задач игнорируются.
        if (!_allowClose && e.CloseReason != CloseReason.WindowsShutDown) e.Cancel = true;
        base.OnFormClosing(e);
    }

    protected override bool ShowWithoutActivation => false;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= 0x00000080; // WS_EX_TOOLWINDOW: не показывать в Alt+Tab
            return cp;
        }
    }
}
