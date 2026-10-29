namespace FocusWall.Desktop;

/// <summary>Значок в трее: статус, быстрый запуск сессии, вход и синхронизация.</summary>
public sealed class TrayApp : ApplicationContext
{
    readonly NotifyIcon _icon;
    readonly Enforcer _enforcer;
    readonly System.Windows.Forms.Timer _syncTimer = new() { Interval = 60_000 };
    readonly SynchronizationContext _ui;

    public TrayApp()
    {
        _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        _enforcer = new Enforcer();
        _icon = new NotifyIcon { Icon = Program.AppIcon, Visible = true, Text = "FocusWall" };
        _icon.ContextMenuStrip = new ContextMenuStrip();
        _icon.ContextMenuStrip.Opening += (_, _) => BuildMenu();
        BuildMenu();

        Store.Changed += () => _ui.Post(_ => _enforcer.Tick(), null);
        _syncTimer.Tick += async (_, _) => await Firebase.Sync();
        _syncTimer.Start();
        _ = Firebase.Sync();
    }

    void BuildMenu()
    {
        var st = Store.Get();
        var now = DateTime.Now;
        var menu = _icon.ContextMenuStrip!;
        menu.Items.Clear();

        var until = Rules.BlockedUntil(st.Config, st.Session, now);
        var hard = Rules.HardActive(st.Config, st.Session, now);
        var status = until == null ? "Блокировка выключена" : $"Блокировка до {until:HH:mm} (ещё {Rules.FormatLeft(until.Value - now)})";
        menu.Items.Add(new ToolStripMenuItem(status) { Enabled = false });
        _icon.Text = status.Length > 63 ? status[..63] : status;

        var schedules = string.Join(", ", st.Config.Schedules.Where(s => s.Enabled).Select(s => $"{s.Name} {s.Start}–{s.End}"));
        if (schedules != "") menu.Items.Add(new ToolStripMenuItem($"Расписание: {schedules}") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());

        if (Rules.SessionActive(st.Session))
        {
            var stop = new ToolStripMenuItem("Остановить сессию") { Enabled = !st.Session!.Hard };
            stop.Click += (_, _) => Store.Update(s =>
            {
                s.Session = null;
                s.SessionUpdatedAt = Rules.NowMs();
            });
            menu.Items.Add(stop);
        }
        else
        {
            var start = new ToolStripMenuItem("Заблокировать компьютер сейчас");
            foreach (var (minutes, label) in new[] { (25, "на 25 минут"), (50, "на 50 минут"), (90, "на 1,5 часа"), (180, "на 3 часа") })
            {
                var item = new ToolStripMenuItem(label);
                item.Click += (_, _) => StartFullLock(minutes);
                start.DropDownItems.Add(item);
            }
            menu.Items.Add(start);
        }

        // Выбор звука для сна (играет во время ночной блокировки).
        var sound = new ToolStripMenuItem("Звук для сна");
        foreach (var choice in SleepSound.All())
        {
            var item = new ToolStripMenuItem(choice.Title) { Checked = choice.Id == st.SleepSound };
            item.Click += (_, _) =>
            {
                Store.Update(s => s.SleepSound = choice.Id);
                SleepSound.Play(choice);
                // Если блокировки сейчас нет, проигрываем короткую пробу и останавливаем.
                if (!Rules.HardActive(st.Config, st.Session, DateTime.Now)) PreviewSound(choice);
            };
            sound.DropDownItems.Add(item);
        }
        sound.DropDownItems.Add(new ToolStripSeparator());
        var addSound = new ToolStripMenuItem("Добавить свой звук (mp3)…");
        addSound.Click += (_, _) =>
        {
            SleepSound.EnsureSoundsDir();
            System.Diagnostics.Process.Start("explorer.exe", SleepSound.SoundsFolder);
        };
        sound.DropDownItems.Add(addSound);
        menu.Items.Add(sound);

        menu.Items.Add(new ToolStripSeparator());

        if (!Firebase.Settings.Configured)
        {
            menu.Items.Add(new ToolStripMenuItem("Синхронизация не настроена") { Enabled = false });
        }
        else if (st.Auth != null)
        {
            var info = st.SyncError != "" ? $"Ошибка синхронизации: {st.SyncError}"
                : st.LastSync > 0 ? $"Синхронизировано в {DateTimeOffset.FromUnixTimeMilliseconds(st.LastSync).LocalDateTime:HH:mm}" : "";
            menu.Items.Add(new ToolStripMenuItem(st.Auth.Email) { Enabled = false });
            if (info != "") menu.Items.Add(new ToolStripMenuItem(info) { Enabled = false });
            var sync = new ToolStripMenuItem("Синхронизировать");
            sync.Click += async (_, _) => await Firebase.Sync();
            menu.Items.Add(sync);
            var signOut = new ToolStripMenuItem("Выйти из аккаунта") { Enabled = !hard };
            signOut.Click += (_, _) => Store.Update(s => s.Auth = null);
            menu.Items.Add(signOut);
        }
        else
        {
            var signIn = new ToolStripMenuItem("Войти через Google…");
            signIn.Click += async (_, _) =>
            {
                try
                {
                    var auth = await Firebase.SignInWithGoogle();
                    Store.Update(s => s.Auth = auth);
                    await Firebase.Sync();
                    _icon.ShowBalloonTip(3000, "FocusWall", $"Вход выполнен: {auth.Email}", ToolTipIcon.Info);
                }
                catch (Exception e)
                {
                    _icon.ShowBalloonTip(5000, "FocusWall", $"Не удалось войти: {e.Message}", ToolTipIcon.Error);
                }
            };
            menu.Items.Add(signIn);
        }

        menu.Items.Add(new ToolStripSeparator());
        var exit = new ToolStripMenuItem(hard ? "Выход недоступен во время блокировки" : "Выход") { Enabled = !hard };
        exit.Click += (_, _) => ExitThread();
        menu.Items.Add(exit);
    }

    System.Windows.Forms.Timer? _preview;

    /// <summary>Короткая проба звука вне блокировки: играет несколько секунд и выключается.</summary>
    void PreviewSound(SleepSound.Choice choice)
    {
        _preview?.Dispose();
        if (choice.Id == SleepSound.None.Id) { SleepSound.Stop(); return; }
        _preview = new System.Windows.Forms.Timer { Interval = 6000 };
        _preview.Tick += (_, _) =>
        {
            _preview!.Stop();
            if (!Rules.HardActive(Store.Get().Config, Store.Get().Session, DateTime.Now)) SleepSound.Stop();
        };
        _preview.Start();
    }

    /// <summary>Сессия с белым списком без программ, то есть полная блокировка компьютера.</summary>
    static void StartFullLock(int minutes)
    {
        const string listId = "desktop-full-lock";
        Store.Update(s =>
        {
            if (s.Config.Blocklists.All(l => l.Id != listId))
            {
                s.Config.Blocklists.Add(new BlockList { Id = listId, Name = "Компьютер: полная блокировка", Mode = "allow" });
                s.Config.UpdatedAt = Rules.NowMs();
            }
            var now = Rules.NowMs();
            s.Session = new Session
            {
                Id = Guid.NewGuid().ToString(),
                ListIds = { listId },
                StartedAt = now,
                EndsAt = now + minutes * 60_000L,
                Hard = true,
                Device = "windows",
            };
            s.SessionUpdatedAt = now;
        });
        _ = Firebase.Sync();
    }

    protected override void ExitThreadCore()
    {
        _icon.Visible = false;
        _icon.Dispose();
        _enforcer.Dispose();
        base.ExitThreadCore();
    }
}
