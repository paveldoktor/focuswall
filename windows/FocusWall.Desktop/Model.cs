using System.Text.Json;
using System.Text.Json.Serialization;

namespace FocusWall.Desktop;

// Формат совпадает с extension/core.js и Android (Model.kt): хранится в Firestore как JSON-строка.

public sealed class BlockList
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    /// <summary>"block" — блокировать перечисленное, "allow" — всё, кроме перечисленного.</summary>
    public string Mode { get; set; } = "block";
    public List<string> Domains { get; set; } = new();
    public List<string> AndroidApps { get; set; } = new();
    public List<string> DesktopApps { get; set; } = new();
}

public sealed class Schedule
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public List<string> ListIds { get; set; } = new();
    /// <summary>0 = воскресенье … 6 = суббота.</summary>
    public List<int> Days { get; set; } = new();
    public string Start { get; set; } = "00:00";
    public string End { get; set; } = "00:00";
    public bool Enabled { get; set; } = true;
    public bool Hard { get; set; }
}

public sealed class Config
{
    public List<BlockList> Blocklists { get; set; } = new();
    public List<Schedule> Schedules { get; set; } = new();
    public long UpdatedAt { get; set; }

    public static Config Default() => new()
    {
        Blocklists =
        {
            new BlockList
            {
                Id = "night", Name = "Ночь: только звонки и Сбербанк", Mode = "allow",
                AndroidApps =
                {
                    "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "ru.sberbankmobile",
                    "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
                },
            },
            new BlockList
            {
                Id = "social", Name = "Соцсети",
                Domains = { "vk.com", "instagram.com", "facebook.com", "tiktok.com", "x.com", "twitter.com", "reddit.com", "ok.ru" },
            },
            new BlockList { Id = "video", Name = "Видео", Domains = { "youtube.com", "twitch.tv", "rutube.ru" } },
        },
        Schedules =
        {
            new Schedule
            {
                Id = "night", Name = "Ночь", ListIds = { "night" }, Days = { 0, 1, 2, 3, 4, 5, 6 },
                Start = "21:00", End = "05:00", Enabled = true, Hard = true,
            },
        },
    };
}

public sealed class Session
{
    public string Id { get; set; } = "";
    public List<string> ListIds { get; set; } = new();
    public long StartedAt { get; set; }
    public long EndsAt { get; set; }
    public bool Hard { get; set; }
    public string Device { get; set; } = "";
}

public static class Json
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        WriteIndented = false,
    };

    public static string Write<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T? Read<T>(string? s) where T : class =>
        string.IsNullOrWhiteSpace(s) || s == "null" ? null : JsonSerializer.Deserialize<T>(s, Options);
}
