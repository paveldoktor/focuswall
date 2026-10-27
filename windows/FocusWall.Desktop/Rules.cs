namespace FocusWall.Desktop;

public enum PolicyMode { Off, Block, Allow }

/// <summary>Block: запрещено то, что в Items. Allow: запрещено всё, кроме Items.</summary>
public sealed record Policy(PolicyMode Mode, HashSet<string> Items)
{
    /// <summary>Белый список без единой программы: компьютер блокируется полностью.</summary>
    public bool FullLock => Mode == PolicyMode.Allow && Items.Count == 0;
}

// Та же логика, что в extension/core.js и Android Policy.kt.
public static class Rules
{
    static int MinutesOf(string hhmm)
    {
        var p = hhmm.Split(':');
        int.TryParse(p.ElementAtOrDefault(0), out var h);
        int.TryParse(p.ElementAtOrDefault(1), out var m);
        return h * 60 + m;
    }

    public static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public static bool ScheduleActive(Schedule s, DateTime now)
    {
        if (!s.Enabled) return false;
        int start = MinutesOf(s.Start), end = MinutesOf(s.End);
        int t = now.Hour * 60 + now.Minute;
        int today = (int)now.DayOfWeek;
        int yesterday = (today + 6) % 7;
        if (start == end) return s.Days.Contains(today);
        if (start < end) return s.Days.Contains(today) && t >= start && t < end;
        return (s.Days.Contains(today) && t >= start) || (s.Days.Contains(yesterday) && t < end);
    }

    public static bool SessionActive(Session? s) => s != null && s.EndsAt > NowMs();

    public static HashSet<string> ActiveListIds(Config c, Session? s, DateTime now)
    {
        var ids = new HashSet<string>();
        if (SessionActive(s)) ids.UnionWith(s!.ListIds);
        foreach (var sc in c.Schedules.Where(x => ScheduleActive(x, now))) ids.UnionWith(sc.ListIds);
        return ids;
    }

    public static Policy DesktopPolicy(Config c, Session? s, DateTime now)
    {
        var ids = ActiveListIds(c, s, now);
        var lists = c.Blocklists.Where(l => ids.Contains(l.Id)).ToList();
        var allowLists = lists.Where(l => l.Mode == "allow").ToList();
        if (allowLists.Count > 0)
        {
            var allowed = allowLists.SelectMany(l => l.DesktopApps).Select(Normalize).ToHashSet();
            foreach (var l in lists.Where(l => l.Mode != "allow")) allowed.ExceptWith(l.DesktopApps.Select(Normalize));
            return new Policy(PolicyMode.Allow, allowed);
        }
        var blocked = lists.SelectMany(l => l.DesktopApps).Select(Normalize).ToHashSet();
        return blocked.Count == 0 ? new Policy(PolicyMode.Off, blocked) : new Policy(PolicyMode.Block, blocked);
    }

    public static bool HardActive(Config c, Session? s, DateTime now) =>
        (SessionActive(s) && s!.Hard) || c.Schedules.Any(x => x.Hard && ScheduleActive(x, now));

    public static DateTime? BlockedUntil(Config c, Session? s, DateTime now)
    {
        DateTime? until = SessionActive(s) ? DateTimeOffset.FromUnixTimeMilliseconds(s!.EndsAt).LocalDateTime : null;
        foreach (var sc in c.Schedules.Where(x => ScheduleActive(x, now)))
        {
            var m = MinutesOf(sc.End);
            var end = now.Date.AddMinutes(m);
            if (end <= now) end = end.AddDays(1);
            if (until == null || end > until) until = end;
        }
        return until;
    }

    public static string Normalize(string exe)
    {
        var s = exe.Trim().ToLowerInvariant();
        return s.EndsWith(".exe") ? s : s + ".exe";
    }

    public static string FormatLeft(TimeSpan left)
    {
        var total = Math.Max(0, (int)Math.Ceiling(left.TotalMinutes));
        return total >= 60 ? $"{total / 60} ч {total % 60} мин" : $"{total} мин";
    }
}
