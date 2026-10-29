namespace FocusWall.Desktop;

/// <summary>Полноэкранный экран блокировки поверх всех окон. Закрыть его может только сама программа.</summary>
public sealed class LockForm : Form
{
    readonly Label _left;
    bool _allowClose;

    public LockForm(Screen screen, bool withControls)
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

        // Выбор звука для сна показываем только на главном экране.
        if (withControls)
        {
            var sounds = new FlowLayoutPanel
            {
                AutoSize = true,
                Anchor = AnchorStyles.Top,
                FlowDirection = FlowDirection.LeftToRight,
                Padding = new Padding(0, 24, 0, 0),
            };
            var caption = new Label
            {
                Text = "Звук для сна:",
                ForeColor = _left.ForeColor,
                Font = new Font("Segoe UI", 12),
                AutoSize = true,
                Margin = new Padding(0, 8, 8, 0),
            };
            var box = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Font = new Font("Segoe UI", 12),
                Width = 220,
            };
            foreach (var c in SleepSound.All()) box.Items.Add(c);
            box.DisplayMember = nameof(SleepSound.Choice.Title);
            var current = Store.Get().SleepSound;
            box.SelectedIndex = Math.Max(0, box.Items.Cast<SleepSound.Choice>().ToList().FindIndex(c => c.Id == current));
            box.SelectedIndexChanged += (_, _) =>
            {
                if (box.SelectedItem is SleepSound.Choice ch)
                {
                    Store.Update(s => s.SleepSound = ch.Id);
                    SleepSound.Play(ch);
                }
            };
            sounds.Controls.Add(caption);
            sounds.Controls.Add(box);
            panel.Controls.Add(sounds, 0, 4);
            panel.RowStyles[4] = new RowStyle(SizeType.Percent, 50);
        }

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
