using System.Runtime.InteropServices;

namespace FocusWall.Desktop;

/// <summary>
/// Звуки для засыпания на экране ночной блокировки.
/// Встроенные звуки (дождь, шум, волны) генерируются сами в WAV, поэтому не нужны чужие файлы.
/// Свои mp3/wav пользователь кладёт в %APPDATA%\FocusWall\sounds и выбирает из списка.
/// Воспроизведение через winmm (mciSendString): умеет и wav, и mp3, и зацикливание.
/// </summary>
public static class SleepSound
{
    [DllImport("winmm.dll", CharSet = CharSet.Unicode)]
    static extern int mciSendString(string command, string? ret, int retLen, IntPtr hwnd);

    public sealed record Choice(string Id, string Title, bool Builtin, string? Path = null);

    public static readonly Choice None = new("none", "Без звука", true);

    static readonly Choice[] Builtins =
    {
        None,
        new("rain", "Дождь", true),
        new("white", "Белый шум", true),
        new("brown", "Коричневый шум", true),
        new("waves", "Волны", true),
    };

    static readonly string SoundsDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "FocusWall", "sounds");

    static readonly object Gate = new();
    static string? _playingAlias;

    /// <summary>Встроенные звуки плюс файлы из папки пользователя.</summary>
    public static IReadOnlyList<Choice> All()
    {
        var list = new List<Choice>(Builtins);
        try
        {
            if (Directory.Exists(SoundsDir))
            {
                foreach (var f in Directory.EnumerateFiles(SoundsDir)
                             .Where(f => f.EndsWith(".mp3", StringComparison.OrdinalIgnoreCase) ||
                                         f.EndsWith(".wav", StringComparison.OrdinalIgnoreCase))
                             .OrderBy(f => f))
                {
                    list.Add(new Choice("file:" + f, Path.GetFileNameWithoutExtension(f), false, f));
                }
            }
        }
        catch
        {
            // Папки может не быть — только встроенные звуки.
        }
        return list;
    }

    public static Choice Find(string? id)
    {
        if (string.IsNullOrEmpty(id)) return None;
        return All().FirstOrDefault(c => c.Id == id) ?? None;
    }

    public static void EnsureSoundsDir()
    {
        try { Directory.CreateDirectory(SoundsDir); } catch { }
    }

    public static string SoundsFolder => SoundsDir;

    /// <summary>Запускает выбранный звук по кругу. Повторный вызов с тем же звуком ничего не меняет.</summary>
    public static void Play(Choice choice)
    {
        lock (Gate)
        {
            if (choice.Id == None.Id)
            {
                StopLocked();
                return;
            }
            var path = choice.Builtin ? BuiltinWav(choice.Id) : choice.Path;
            if (path == null || !File.Exists(path))
            {
                StopLocked();
                return;
            }

            var alias = "fw_" + choice.Id.GetHashCode().ToString("x");
            if (_playingAlias == alias) return;
            StopLocked();

            // В кавычках, потому что в пути есть пробелы.
            if (mciSendString($"open \"{path}\" alias {alias}", null, 0, IntPtr.Zero) != 0) return;
            mciSendString($"play {alias} repeat", null, 0, IntPtr.Zero);
            _playingAlias = alias;
        }
    }

    public static void Stop()
    {
        lock (Gate) StopLocked();
    }

    static void StopLocked()
    {
        if (_playingAlias == null) return;
        mciSendString($"stop {_playingAlias}", null, 0, IntPtr.Zero);
        mciSendString($"close {_playingAlias}", null, 0, IntPtr.Zero);
        _playingAlias = null;
    }

    // ----- Генерация встроенных звуков -----

    static readonly object WavGate = new();

    static string BuiltinWav(string id)
    {
        var path = Path.Combine(Path.GetTempPath(), $"focuswall-{id}.wav");
        lock (WavGate)
        {
            if (!File.Exists(path)) File.WriteAllBytes(path, Generate(id));
        }
        return path;
    }

    const int SampleRate = 44100;
    const int Seconds = 20; // короткий отрезок, дальше зацикливается

    static byte[] Generate(string id)
    {
        int n = SampleRate * Seconds;
        var samples = new float[n];
        var rnd = new Random(1);
        float last = 0, lastBrown = 0;

        for (int i = 0; i < n; i++)
        {
            float white = (float)(rnd.NextDouble() * 2 - 1);
            float v = id switch
            {
                // Белый шум мягко сглажен, иначе режет слух.
                "white" => last = last * 0.75f + white * 0.25f,
                // Коричневый шум — интеграл белого, глухой и ровный.
                "brown" => lastBrown = Math.Clamp(lastBrown + white * 0.02f, -1f, 1f) * 0.98f,
                // Дождь — отфильтрованный шум с частым негромким потрескиванием.
                "rain" => (last = last * 0.6f + white * 0.4f) * 0.5f + (rnd.NextDouble() < 0.0006 ? white * 0.5f : 0),
                // Волны — шум с медленным приливом и отливом громкости.
                "waves" => (last = last * 0.85f + white * 0.15f) *
                           (0.35f + 0.35f * (float)Math.Abs(Math.Sin(i * 2 * Math.PI / (SampleRate * 9.0)))),
                _ => white,
            };
            samples[i] = Math.Clamp(v, -1f, 1f);
        }

        // Склейка петли без щелчка: короткий кросс-фейд конца с началом.
        int fade = SampleRate / 2;
        for (int i = 0; i < fade; i++)
        {
            float t = i / (float)fade;
            samples[i] = samples[i] * t + samples[n - fade + i] * (1 - t);
        }

        return WriteWav(samples);
    }

    static byte[] WriteWav(float[] samples)
    {
        using var ms = new MemoryStream();
        using var w = new BinaryWriter(ms);
        int dataBytes = samples.Length * 2;
        w.Write("RIFF".ToCharArray());
        w.Write(36 + dataBytes);
        w.Write("WAVE".ToCharArray());
        w.Write("fmt ".ToCharArray());
        w.Write(16);
        w.Write((short)1); // PCM
        w.Write((short)1); // моно
        w.Write(SampleRate);
        w.Write(SampleRate * 2); // байт в секунду
        w.Write((short)2); // байт на кадр
        w.Write((short)16); // бит на отсчёт
        w.Write("data".ToCharArray());
        w.Write(dataBytes);
        foreach (var s in samples) w.Write((short)(s * 32767));
        w.Flush();
        return ms.ToArray();
    }
}
