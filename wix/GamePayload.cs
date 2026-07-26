using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace RSetup
{
    /// <summary>
    /// 从本机原版游戏根目录暂存一体包所需的游戏本体（排除用户数据与冗余 JVM）。
    /// 路径优先级：环境变量 RW_GAME_ROOT → packaging/game-root.local.txt → 默认 Steam 路径。
    /// </summary>
    internal static class GamePayload
    {
        public const string GameRootEnvVar = "RW_GAME_ROOT";
        public const string LocalPathFileRelative = @"packaging\game-root.local.txt";
        public const string DefaultGameRoot = @"D:\APP\Steam\steamapps\common\Rusted Warfare";

        private static readonly HashSet<string> ExcludedTopDirs = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "cache",
            "saves",
            "replays",
            "generated_lib",
            "jvm",
            "jvm64",
            // 启动器自带 runtime/，不打包原版 JVM
        };

        private static readonly HashSet<string> ExcludedFileNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "rwpp-log.txt",
            "lastrun.log",
            "preferences.ini",
            "logo.ico",
            "RWJS.exe",
            "RWPP.exe",
            "launcher.bat",
            "launcher.sh",
        };

        public static string ResolveGameRoot(string repoRoot)
        {
            string fromEnv = Environment.GetEnvironmentVariable(GameRootEnvVar);
            if (!string.IsNullOrWhiteSpace(fromEnv))
                return Path.GetFullPath(fromEnv.Trim().Trim('"'));

            string localFile = Path.Combine(repoRoot, LocalPathFileRelative);
            if (File.Exists(localFile))
            {
                string line = File.ReadAllLines(localFile)
                    .Select(l => l.Trim())
                    .FirstOrDefault(l => l.Length > 0 && !l.StartsWith("#", StringComparison.Ordinal));
                if (!string.IsNullOrWhiteSpace(line))
                    return Path.GetFullPath(line.Trim().Trim('"'));
            }

            return Path.GetFullPath(DefaultGameRoot);
        }

        public static void EnsureGameRootReady(string gameRoot)
        {
            if (!Directory.Exists(gameRoot))
                throw new DirectoryNotFoundException(
                    $"未找到原版游戏目录: {gameRoot}\n" +
                    $"请设置环境变量 {GameRootEnvVar}，或在 {LocalPathFileRelative} 中写入游戏根路径。");

            string gameLib = Path.Combine(gameRoot, "game-lib.jar");
            if (!File.Exists(gameLib))
                throw new FileNotFoundException(
                    $"游戏目录缺少 game-lib.jar，无法打一体包: {gameRoot}");

            string assets = Path.Combine(gameRoot, "assets");
            if (!Directory.Exists(assets))
                throw new DirectoryNotFoundException(
                    $"游戏目录缺少 assets/，无法打一体包: {gameRoot}");
        }

        /// <summary>
        /// 将过滤后的游戏文件复制到 build/tmp/game-payload，供 WiX 相对路径打包。
        /// </summary>
        public static string Stage(string repoRoot, string gameRoot)
        {
            EnsureGameRootReady(gameRoot);

            string stagingRoot = Path.Combine(repoRoot, "build", "tmp", "game-payload");
            if (Directory.Exists(stagingRoot))
                Directory.Delete(stagingRoot, true);
            Directory.CreateDirectory(stagingRoot);

            int copied = 0;
            long bytes = 0;

            foreach (string sourceFile in Directory.EnumerateFiles(gameRoot, "*", SearchOption.AllDirectories))
            {
                string relative = sourceFile.Substring(gameRoot.Length)
                    .TrimStart(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                if (!ShouldInclude(relative))
                    continue;

                string destFile = Path.Combine(stagingRoot, relative);
                Directory.CreateDirectory(Path.GetDirectoryName(destFile) ?? stagingRoot);
                File.Copy(sourceFile, destFile, overwrite: true);
                copied++;
                bytes += new FileInfo(sourceFile).Length;
            }

            if (copied == 0)
                throw new InvalidOperationException($"游戏本体暂存结果为空，请检查目录: {gameRoot}");

            Console.WriteLine($"[GamePayload] source={gameRoot}");
            Console.WriteLine($"[GamePayload] staged={stagingRoot} files={copied} sizeMB={bytes / (1024.0 * 1024.0):F1}");
            return stagingRoot;
        }

        public static bool ShouldInclude(string relativePath)
        {
            if (string.IsNullOrWhiteSpace(relativePath))
                return false;

            string normalized = relativePath.Replace('/', '\\');
            string[] parts = normalized.Split(new[] { '\\' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0)
                return false;

            if (ExcludedTopDirs.Contains(parts[0]))
                return false;

            string fileName = parts[parts.Length - 1];
            if (ExcludedFileNames.Contains(fileName))
                return false;

            if (fileName.StartsWith("io.github.rwpp.", StringComparison.OrdinalIgnoreCase))
                return false;

            if (fileName.EndsWith(".toml", StringComparison.OrdinalIgnoreCase))
                return false;

            if (fileName.EndsWith(".bak", StringComparison.OrdinalIgnoreCase))
                return false;

            if (fileName.EndsWith(".log", StringComparison.OrdinalIgnoreCase))
                return false;

            return true;
        }
    }
}
