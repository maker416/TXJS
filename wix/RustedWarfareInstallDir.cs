using System;
using System.IO;

namespace RSetup
{
    internal static class RustedWarfareInstallDir
    {
        private const string SteamAppId = "647960";

        public static string Normalize(string path)
        {
            if (string.IsNullOrWhiteSpace(path))
                return null;

            string normalized = path.Trim().Trim('"');
            try
            {
                normalized = Path.GetFullPath(normalized);
            }
            catch
            {
                return null;
            }

            string root = Path.GetPathRoot(normalized);
            string trimmed = normalized.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (!string.IsNullOrEmpty(root) &&
                string.Equals(trimmed, root.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar), StringComparison.OrdinalIgnoreCase))
            {
                return root;
            }

            return trimmed;
        }

        public static bool LooksLikeOriginalGameDir(string path) =>
            TryFindOriginalFeature(path, out _, out _, out _);

        public static bool TryFindOriginalFeature(string path, out string normalized, out string feature, out string reason)
        {
            normalized = Normalize(path);
            feature = null;

            if (string.IsNullOrEmpty(normalized))
            {
                reason = "安装路径不能为空，且必须是一个可解析的本地目录。";
                return false;
            }

            if (!Directory.Exists(normalized))
            {
                reason = $"目录不存在: {normalized}";
                return false;
            }

            if (HasSteamAppId(normalized))
            {
                feature = "steam_appid.txt=647960";
                reason = null;
                return true;
            }

            string[] fileFeatures =
            {
                "Rusted Warfare.exe",
                "Rusted Warfare - 64.exe",
                "Rusted Warfare - 64.exe.bak",
                "game-lib.jar",
                "steam_api.dll",
                "steam_api64.dll",
            };

            foreach (string file in fileFeatures)
            {
                if (File.Exists(Path.Combine(normalized, file)))
                {
                    feature = file;
                    reason = null;
                    return true;
                }
            }

            string[] directoryFeatures =
            {
                Path.Combine("assets", "units"),
                Path.Combine("assets", "maps"),
                Path.Combine("assets", "translations"),
                Path.Combine("res", "drawable"),
                Path.Combine("res", "raw"),
                Path.Combine("libs", "lwjgl.jar"),
            };

            foreach (string relativePath in directoryFeatures)
            {
                string fullPath = Path.Combine(normalized, relativePath);
                if (Directory.Exists(fullPath) || File.Exists(fullPath))
                {
                    feature = relativePath;
                    reason = null;
                    return true;
                }
            }

            reason = "未检测到原版 Rusted Warfare 特征。至少需要存在 steam_appid.txt=647960、原版 exe、game-lib.jar、assets/res/libs 结构或 Steam API 文件中的任意一个。";
            return false;
        }

        public static string BuildInvalidPathMessage(string path, string reason)
        {
            string normalized = Normalize(path) ?? path ?? string.Empty;
            return "请选择原版铁锈战争（Rusted Warfare）的游戏根目录。\n\n" +
                   $"当前路径: {normalized}\n" +
                   $"原因: {reason}\n\n" +
                   @"示例: D:\APP\Steam\steamapps\common\Rusted Warfare";
        }

        private static bool HasSteamAppId(string normalized)
        {
            string appIdPath = Path.Combine(normalized, "steam_appid.txt");
            if (!File.Exists(appIdPath))
                return false;

            try
            {
                return string.Equals(File.ReadAllText(appIdPath).Trim(), SteamAppId, StringComparison.Ordinal);
            }
            catch
            {
                return false;
            }
        }
    }
}
