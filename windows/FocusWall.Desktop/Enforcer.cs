using System.Diagnostics;
using System.Runtime.InteropServices;

namespace FocusWall.Desktop;

/// <summary>Каждые 2 секунды сверяет запущенные программы с текущей политикой.</summary>
public sealed class Enforcer : IDisposable
{
    [DllImport("user32.dll")] static extern bool LockWorkStation();
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);

    /// <summary>Без этих процессов Windows не работает; их никогда не закрываем.</summary>
    static readonly HashSet<string> SystemProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "focuswall.exe", "explorer.exe", "dwm.exe", "csrss.exe", "winlogon.exe", "logonui.exe", "lockapp.exe",
        "sihost.exe", "ctfmon.exe", "textinputhost.exe", "shellexperiencehost.exe", "startmenuexperiencehost.exe",
        "searchhost.exe", "searchapp.exe", "applicationframehost.exe", "systemsettingsbroker.exe", "taskmgr.exe",
        "runtimebroker.exe", "securityhealthsystray.exe", "consent.exe", "credentialuibroker.exe",
    };

    readonly System.Windows.Forms.Timer _timer = new() { Interval = 2000 };
    readonly List<LockForm> _locks = new();
    readonly Dictionary<int, DateTime> _closing = new();
    DateTime _notOnTopSince = DateTime.MaxValue;

    public Policy Current { get; private set; } = new(PolicyMode.Off, new());

    public Enforcer()
    {
        _timer.Tick += (_, _) => Tick();
        _timer.Start();
        Tick();
    }

    public void Tick()
    {
        var st = Store.Get();
        var now = DateTime.Now;
        Current = Rules.DesktopPolicy(st.Config, st.Session, now);

        if (Current.FullLock) ShowLock(Rules.BlockedUntil(st.Config, st.Session, now));
        else HideLock();

        if (Current.Mode == PolicyMode.Off || Current.FullLock)
        {
            _closing.Clear();
            return;
        }
        foreach (var p in Process.GetProcesses())
        {
            try
            {
                if (p.SessionId == 0) continue;
                var exe = p.ProcessName.ToLowerInvariant() + ".exe";
                if (SystemProcesses.Contains(exe)) continue;
                bool blocked = Current.Mode == PolicyMode.Block ? Current.Items.Contains(exe) : !Current.Items.Contains(exe);
                // В белом списке трогаем только программы с окнами: фоновые службы и драйверы не закрываем.
                if (!blocked || (Current.Mode == PolicyMode.Allow && p.MainWindowHandle == IntPtr.Zero)) continue;
                Close(p);
            }
            catch
            {
                // Нет прав или процесс уже завершился.
            }
            finally
            {
                p.Dispose();
            }
        }
    }

    /// <summary>Сначала просим закрыться (чтобы программа предложила сохранить файлы), через 15 секунд закрываем принудительно.</summary>
    void Close(Process p)
    {
        if (!_closing.TryGetValue(p.Id, out var since))
        {
            _closing[p.Id] = DateTime.Now;
            p.CloseMainWindow();
            return;
        }
        if (DateTime.Now - since > TimeSpan.FromSeconds(15))
        {
            p.Kill(entireProcessTree: true);
            _closing.Remove(p.Id);
        }
    }

    void ShowLock(DateTime? until)
    {
        if (_locks.Count != Screen.AllScreens.Length)
        {
            HideLock();
            foreach (var screen in Screen.AllScreens)
            {
                var f = new LockForm(screen);
                f.Show();
                _locks.Add(f);
            }
        }
        foreach (var f in _locks)
        {
            f.SetUntil(until);
            f.TopMost = true;
            f.BringToFront();
        }

        // Если поверх экрана блокировки оказалось другое окно (например, Диспетчер задач) дольше 6 секунд,
        // блокируем сеанс Windows.
        var fg = GetForegroundWindow();
        if (_locks.Any(f => f.Handle == fg))
        {
            _notOnTopSince = DateTime.MaxValue;
            return;
        }
        SetForegroundWindow(_locks[0].Handle);
        if (_notOnTopSince == DateTime.MaxValue) _notOnTopSince = DateTime.Now;
        else if (DateTime.Now - _notOnTopSince > TimeSpan.FromSeconds(6))
        {
            LockWorkStation();
            _notOnTopSince = DateTime.MaxValue;
        }
    }

    void HideLock()
    {
        foreach (var f in _locks) f.ForceClose();
        _locks.Clear();
        _notOnTopSince = DateTime.MaxValue;
    }

    public void Dispose()
    {
        _timer.Dispose();
        HideLock();
    }
}
