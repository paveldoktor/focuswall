namespace FocusWall.Desktop;

public sealed class Auth
{
    public string Uid { get; set; } = "";
    public string Email { get; set; } = "";
    public string IdToken { get; set; } = "";
    public string RefreshToken { get; set; } = "";
    public long ExpiresAt { get; set; }
}

/// <summary>Локальное состояние в %APPDATA%\FocusWall\state.json.</summary>
public sealed class State
{
    public Config Config { get; set; } = Config.Default();
    public Session? Session { get; set; }
    public long SessionUpdatedAt { get; set; }
    public Auth? Auth { get; set; }
    public long LastSync { get; set; }
    public string SyncError { get; set; } = "";
}

public static class Store
{
    static readonly object Gate = new();
    static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "FocusWall");
    static readonly string FilePath = Path.Combine(Dir, "state.json");
    static State? _state;

    public static event Action? Changed;

    public static State Get()
    {
        lock (Gate)
        {
            if (_state != null) return _state;
            try
            {
                _state = File.Exists(FilePath) ? Json.Read<State>(File.ReadAllText(FilePath)) : null;
            }
            catch
            {
                _state = null;
            }
            return _state ??= new State();
        }
    }

    public static void Update(Action<State> change)
    {
        lock (Gate)
        {
            var s = Get();
            change(s);
            Directory.CreateDirectory(Dir);
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, Json.Write(s));
            File.Move(tmp, FilePath, overwrite: true);
        }
        Changed?.Invoke();
    }
}
