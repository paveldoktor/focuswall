using System.Diagnostics;
using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace FocusWall.Desktop;

public sealed class FirebaseSettings
{
    public string ApiKey { get; set; } = "";
    public string ProjectId { get; set; } = "";
    /// <summary>OAuth-клиент Google типа «Desktop app».</summary>
    public string DesktopClientId { get; set; } = "";
    public string DesktopClientSecret { get; set; } = "";

    public bool Configured => ApiKey != "" && ProjectId != "" && DesktopClientId != "";

    /// <summary>firebase.json рядом с exe или встроенный при сборке.</summary>
    public static FirebaseSettings Load()
    {
        try
        {
            var local = Path.Combine(AppContext.BaseDirectory, "firebase.json");
            if (File.Exists(local)) return Json.Read<FirebaseSettings>(File.ReadAllText(local)) ?? new();
            using var res = Assembly.GetExecutingAssembly().GetManifestResourceStream("firebase.json");
            if (res != null) return Json.Read<FirebaseSettings>(new StreamReader(res).ReadToEnd()) ?? new();
        }
        catch
        {
            // Без настроек работаем локально.
        }
        return new();
    }
}

/// <summary>Firebase через REST, как в расширении и в Android-приложении.</summary>
public static class Firebase
{
    public static readonly FirebaseSettings Settings = FirebaseSettings.Load();
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };

    static async Task<JsonNode> Send(HttpRequestMessage req)
    {
        var res = await Http.SendAsync(req);
        var text = await res.Content.ReadAsStringAsync();
        var json = string.IsNullOrEmpty(text) ? new JsonObject() : JsonNode.Parse(text)!;
        if (!res.IsSuccessStatusCode)
        {
            var msg = json["error"]?["message"]?.GetValue<string>() ?? json["error_description"]?.GetValue<string>();
            throw new Exception(msg ?? $"HTTP {(int)res.StatusCode}");
        }
        return json;
    }

    // ---------- Вход ----------

    /// <summary>Вход через Google в системном браузере (loopback + PKCE), затем вход в Firebase.</summary>
    public static async Task<Auth> SignInWithGoogle()
    {
        var port = FreePort();
        var redirect = $"http://127.0.0.1:{port}/";
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));

        using var listener = new HttpListener();
        listener.Prefixes.Add(redirect);
        listener.Start();

        var url = "https://accounts.google.com/o/oauth2/v2/auth?" + Query(new()
        {
            ["client_id"] = Settings.DesktopClientId,
            ["redirect_uri"] = redirect,
            ["response_type"] = "code",
            ["scope"] = "openid email profile",
            ["code_challenge"] = challenge,
            ["code_challenge_method"] = "S256",
            ["prompt"] = "select_account",
        });
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

        var ctxTask = listener.GetContextAsync();
        if (await Task.WhenAny(ctxTask, Task.Delay(TimeSpan.FromMinutes(5))) != ctxTask) throw new Exception("Вход не завершён за 5 минут");
        var ctx = ctxTask.Result;
        var code = ctx.Request.QueryString["code"];
        var page = Encoding.UTF8.GetBytes("<html><meta charset='utf-8'><body style='font-family:sans-serif;padding:40px'>" +
            (code != null ? "Вход выполнен, вкладку можно закрыть." : "Вход отменён.") + "</body></html>");
        ctx.Response.ContentType = "text/html; charset=utf-8";
        await ctx.Response.OutputStream.WriteAsync(page);
        ctx.Response.Close();
        if (code == null) throw new Exception("Вход отменён");

        var token = await Send(new HttpRequestMessage(HttpMethod.Post, "https://oauth2.googleapis.com/token")
        {
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["code"] = code,
                ["client_id"] = Settings.DesktopClientId,
                ["client_secret"] = Settings.DesktopClientSecret,
                ["redirect_uri"] = redirect,
                ["grant_type"] = "authorization_code",
                ["code_verifier"] = verifier,
            }),
        });
        var googleIdToken = token["id_token"]!.GetValue<string>();

        var data = await Send(new HttpRequestMessage(HttpMethod.Post,
            $"https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key={Settings.ApiKey}")
        {
            Content = JsonContent.Create(new
            {
                postBody = $"id_token={googleIdToken}&providerId=google.com",
                requestUri = "http://localhost",
                returnSecureToken = true,
            }),
        });
        return new Auth
        {
            Uid = data["localId"]!.GetValue<string>(),
            Email = data["email"]?.GetValue<string>() ?? "",
            IdToken = data["idToken"]!.GetValue<string>(),
            RefreshToken = data["refreshToken"]!.GetValue<string>(),
            ExpiresAt = Rules.NowMs() + long.Parse(data["expiresIn"]!.GetValue<string>()) * 1000,
        };
    }

    public static async Task<Auth> Fresh(Auth auth)
    {
        if (auth.ExpiresAt - Rules.NowMs() > 60_000) return auth;
        var data = await Send(new HttpRequestMessage(HttpMethod.Post, $"https://securetoken.googleapis.com/v1/token?key={Settings.ApiKey}")
        {
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "refresh_token",
                ["refresh_token"] = auth.RefreshToken,
            }),
        });
        return new Auth
        {
            Uid = auth.Uid,
            Email = auth.Email,
            IdToken = data["id_token"]!.GetValue<string>(),
            RefreshToken = data["refresh_token"]!.GetValue<string>(),
            ExpiresAt = Rules.NowMs() + long.Parse(data["expires_in"]!.GetValue<string>()) * 1000,
        };
    }

    // ---------- Firestore ----------

    static string DocUrl(string uid) =>
        $"https://firestore.googleapis.com/v1/projects/{Settings.ProjectId}/databases/(default)/documents/users/{uid}";

    public sealed record Remote(string? Config, long ConfigUpdatedAt, string? Session, long SessionUpdatedAt);

    public static async Task<Remote?> Fetch(Auth auth)
    {
        var req = new HttpRequestMessage(HttpMethod.Get, DocUrl(auth.Uid));
        req.Headers.Authorization = new("Bearer", auth.IdToken);
        var res = await Http.SendAsync(req);
        if (res.StatusCode == HttpStatusCode.NotFound) return null;
        var text = await res.Content.ReadAsStringAsync();
        if (!res.IsSuccessStatusCode) throw new Exception(JsonNode.Parse(text)?["error"]?["message"]?.GetValue<string>() ?? $"HTTP {(int)res.StatusCode}");
        var f = JsonNode.Parse(text)?["fields"];
        string? Str(string k) => f?[k]?["stringValue"]?.GetValue<string>();
        long Int(string k) => long.TryParse(f?[k]?["integerValue"]?.GetValue<string>(), out var v) ? v : 0;
        return new Remote(Str("config"), Int("configUpdatedAt"), Str("session"), Int("sessionUpdatedAt"));
    }

    public static async Task Push(Auth auth, string part, string value, long updatedAt)
    {
        var url = DocUrl(auth.Uid) + $"?updateMask.fieldPaths={part}&updateMask.fieldPaths={part}UpdatedAt";
        var body = new JsonObject
        {
            ["fields"] = new JsonObject
            {
                [part] = new JsonObject { ["stringValue"] = value },
                [$"{part}UpdatedAt"] = new JsonObject { ["integerValue"] = updatedAt.ToString() },
            },
        };
        var req = new HttpRequestMessage(HttpMethod.Patch, url)
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        req.Headers.Authorization = new("Bearer", auth.IdToken);
        await Send(req);
    }

    /// <summary>Побеждает более свежая версия, отдельно для настроек и для сессии.</summary>
    public static async Task Sync()
    {
        if (!Settings.Configured) return;
        var st = Store.Get();
        if (st.Auth == null) return;
        try
        {
            var auth = await Fresh(st.Auth);
            var remote = await Fetch(auth);
            var remoteConfig = Json.Read<Config>(remote?.Config);
            Config? newConfig = null;
            if (remote != null && remoteConfig != null && remote.ConfigUpdatedAt > st.Config.UpdatedAt)
            {
                remoteConfig.UpdatedAt = remote.ConfigUpdatedAt;
                newConfig = remoteConfig;
            }
            else if (remote == null || st.Config.UpdatedAt > remote.ConfigUpdatedAt)
            {
                var ts = st.Config.UpdatedAt > 0 ? st.Config.UpdatedAt : Rules.NowMs();
                st.Config.UpdatedAt = ts;
                await Push(auth, "config", Json.Write(st.Config), ts);
            }

            bool takeSession = remote != null && remote.SessionUpdatedAt > st.SessionUpdatedAt;
            if (!takeSession && st.SessionUpdatedAt > (remote?.SessionUpdatedAt ?? 0))
                await Push(auth, "session", Json.Write(st.Session), st.SessionUpdatedAt);

            Store.Update(s =>
            {
                s.Auth = auth;
                if (newConfig != null) s.Config = newConfig;
                if (takeSession)
                {
                    s.Session = Json.Read<Session>(remote!.Session);
                    s.SessionUpdatedAt = remote.SessionUpdatedAt;
                }
                s.LastSync = Rules.NowMs();
                s.SyncError = "";
            });
        }
        catch (Exception e)
        {
            Store.Update(s => s.SyncError = e.Message);
        }
    }

    static int FreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    static string Base64Url(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    static string Query(Dictionary<string, string> q) =>
        string.Join("&", q.Select(kv => $"{Uri.EscapeDataString(kv.Key)}={Uri.EscapeDataString(kv.Value)}"));
}
