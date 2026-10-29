using System.Diagnostics;
using System.Reflection;
using Microsoft.Win32;

namespace FocusWall.Desktop;

static class Program
{
    public static Icon AppIcon { get; private set; } = SystemIcons.Application;

    [STAThread]
    static void Main(string[] args)
    {
        // Одна копия на пользователя: сторож из Планировщика запускает программу каждую минуту, лишние копии сразу выходят.
        using var mutex = new Mutex(true, @"Local\FocusWall.Desktop", out var first);
        if (!first) return;

        using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("app.ico"))
            if (s != null) AppIcon = new Icon(s);

        RegisterAutostart();
        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp());
    }

    /// <summary>Автозапуск при входе в Windows и сторож, который перезапускает программу, если её закрыли.</summary>
    static void RegisterAutostart()
    {
        var exe = Environment.ProcessPath;
        if (exe == null) return;
        try
        {
            using var run = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            run?.SetValue("FocusWall", $"\"{exe}\"");
        }
        catch
        {
            // Не критично.
        }
        // Сторож: раз в минуту запускает программу, если её закрыли (лишняя копия сразу выходит по мьютексу).
        RunSchtasks($"/Create /F /SC MINUTE /MO 1 /TN \"FocusWall Watchdog\" /TR \"\\\"{exe}\\\"\"");
        // Отдельно — гарантированный запуск каждый вечер в 21:00, к началу ночной блокировки.
        RunSchtasks($"/Create /F /SC DAILY /ST 21:00 /TN \"FocusWall Daily\" /TR \"\\\"{exe}\\\"\"");
    }

    static void RunSchtasks(string args)
    {
        try
        {
            var psi = new ProcessStartInfo("schtasks.exe", args) { CreateNoWindow = true, UseShellExecute = false };
            Process.Start(psi)?.WaitForExit(5000);
        }
        catch
        {
            // Не критично: автозапуск через реестр всё равно прописан.
        }
    }
}
