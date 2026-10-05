using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

// The installer, and the uninstaller when run with --uninstall.
//
// No Inno Setup and no NSIS on the build machine, and neither is a dependency worth adding for
// three files, so this is the installer: it carries the payload as embedded resources, writes it
// somewhere, leaves a way back out, and registers itself in the list Windows shows for installed
// programs.
//
// It installs per user — %LOCALAPPDATA%, no administrator rights, no UAC prompt. A network
// viewer has no business demanding elevation to write in your own profile.
//
// Running it again with --uninstall puts everything back the way it was.

internal static class Setup
{
    private const string AppName = "LanWatch";
    private const string Version = "1.0.0";
    private const string Publisher = "LanWatch";
    private const string RegistryKey =
        @"Software\Microsoft\Windows\CurrentVersion\Uninstall\LanWatch";

    /// <summary>Files to write, mapped from embedded resource name to installed file name.</summary>
    private static readonly Dictionary<string, string> Payload = new Dictionary<string, string>
    {
        { "Payload.LanWatch.exe", "LanWatch.exe" },
        { "Payload.LanWatch.jar", "LanWatch.jar" },
        { "Payload.README.md", "README.md" },
    };

    private static string InstallDir
    {
        get { return Path.Combine(Environment.GetFolderPath(
            Environment.SpecialFolder.LocalApplicationData), AppName); }
    }

    private static string StartMenuLink
    {
        get { return Path.Combine(Environment.GetFolderPath(
            Environment.SpecialFolder.StartMenu), "Programs", AppName + ".lnk"); }
    }

    [STAThread]
    private static int Main(string[] args)
    {
        Application.EnableVisualStyles();

        bool silent = Array.IndexOf(args, "--silent") >= 0;
        bool uninstall = Array.IndexOf(args, "--uninstall") >= 0;
        string dir = ReadOption(args, "--dir");

        try
        {
            if (uninstall) return Uninstall(silent);
            return Install(dir, silent);
        }
        catch (Exception e)
        {
            MessageBox.Show("Не удалось " + (uninstall ? "удалить" : "установить") + ":\n\n"
                + e.Message, AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    // --- installing ---------------------------------------------------------------

    private static int Install(string dir, bool silent)
    {
        string target = string.IsNullOrEmpty(dir) ? InstallDir : dir;

        if (!silent)
        {
            target = AskForFolder(target);
            if (target == null) return 0;      // cancelled
        }

        Directory.CreateDirectory(target);

        Assembly self = Assembly.GetExecutingAssembly();
        foreach (KeyValuePair<string, string> file in Payload)
        {
            using (Stream source = self.GetManifestResourceStream(file.Key))
            {
                if (source == null) throw new InvalidOperationException(
                    "в установщике нет файла " + file.Key + " — он собран не полностью");
                string path = Path.Combine(target, file.Value);
                using (FileStream to = new FileStream(path, FileMode.Create, FileAccess.Write))
                {
                    source.CopyTo(to);
                }
            }
        }

        // The uninstaller is a copy of this file: whatever happens to the original download,
        // there is a way back out from inside the installation.
        File.Copy(Assembly.GetExecutingAssembly().Location,
            Path.Combine(target, "Uninstall.exe"), true);

        CreateShortcuts(target);
        WriteRegistry(target);

        if (!silent)
        {
            MessageBox.Show(
                AppName + " установлен.\n\nПапка: " + target +
                "\n\nПрограмма запускается через LanWatch.exe — он поднимает сервер," +
                " который телефоны ищут в сети сами.",
                AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        return 0;
    }

    private static string AskForFolder(string suggestion)
    {
        using (FolderBrowserDialog dialog = new FolderBrowserDialog())
        {
            dialog.Description = "Куда установить " + AppName + "?";
            dialog.SelectedPath = Directory.Exists(suggestion) ? suggestion : Suggest();
            dialog.ShowNewFolderButton = true;
            return dialog.ShowDialog() == DialogResult.OK ? dialog.SelectedPath : null;
        }
    }

    private static string Suggest()
    {
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            AppName);
    }

    /// <summary>Creates the Start Menu entry, through PowerShell so no COM reference is needed.</summary>
    private static void CreateShortcuts(string target)
    {
        string menu = Environment.GetFolderPath(Environment.SpecialFolder.StartMenu);
        string scripts = Path.Combine(Environment.GetFolderPath(
            Environment.SpecialFolder.StartMenu), "Programs");

        Directory.CreateDirectory(scripts);
        Directory.CreateDirectory(Path.Combine(menu, "Programs"));

        Shortcut(StartMenuLink, Path.Combine(target, "LanWatch.exe"), null);
        Shortcut(Path.Combine(scripts, AppName + " — удалить.exe"),
            Path.Combine(target, "Uninstall.exe"), "--uninstall");
    }

    private static void Shortcut(string link, string target, string arguments)
    {
        string script =
            "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('" +
            link.Replace("'", "''") + "');" +
            "$s.TargetPath = '" + target.Replace("'", "''") + "';" +
            (string.IsNullOrEmpty(arguments) ? "" :
                "$s.Arguments = '" + arguments.Replace("'", "''") + "';") +
            "$s.Description = 'Устройства в сети — сервер на компьютере';" +
            "$s.Save();";

        Run("powershell", "-NoProfile -NonInteractive -WindowStyle Hidden -Command \"" +
            script.Replace("\"", "\\\"") + "\"");
    }

    private static void WriteRegistry(string target)
    {
        using (Microsoft.Win32.RegistryKey key =
            Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RegistryKey))
        {
            key.SetValue("DisplayName", AppName + " — устройства в сети");
            key.SetValue("DisplayVersion", Version);
            key.SetValue("Publisher", Publisher);
            key.SetValue("DisplayIcon", Path.Combine(target, "LanWatch.exe"));
            key.SetValue("InstallLocation", target);
            key.SetValue("UninstallString", "\"" + Path.Combine(target, "Uninstall.exe")
                + "\" --uninstall");
            key.SetValue("QuietUninstallString", "\"" + Path.Combine(target, "Uninstall.exe")
                + "\" --uninstall --silent");
            key.SetValue("NoModify", 1);
            key.SetValue("NoRepair", 1);
        }
    }

    // --- uninstalling -------------------------------------------------------------

    private static int Uninstall(bool silent)
    {
        string dir = AppContext.BaseDirectory;

        if (!silent)
        {
            DialogResult answer = MessageBox.Show(
                "Удалить " + AppName + "?\n\nПрограмма будет закрыта.",
                AppName, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (answer != DialogResult.Yes) return 0;
        }

        // The program may still be running, and holding its files open would leave them behind.
        foreach (string name in new[] { "LanWatch", "javaw", "java" })
        {
            foreach (Process process in SafeProcesses(name))
            {
                if (process.MainModule != null &&
                    process.MainModule.FileName.StartsWith(dir, StringComparison.OrdinalIgnoreCase))
                {
                    try { process.Kill(); process.WaitForExit(5000); }
                    catch (Exception) { /* it may have exited on its own */ }
                }
            }
        }

        Delete(StartMenuLink);
        try
        {
            string scripts = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs");
            Delete(Path.Combine(scripts, AppName + " — удалить.exe"));
        }
        catch (Exception) { /* the menu may not exist */ }

        try { Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(RegistryKey, false); }
        catch (Exception) { /* nothing registered, nothing to remove */ }

        // The uninstaller cannot delete itself while it is running, so it starts a detached copy
        // of the shell that removes the folder a moment later and then disappears.
        string folder = dir.TrimEnd('\\');
        string wait = Path.Combine(Path.GetTempPath(), "lanwatch-remove-" + Guid.NewGuid() + ".cmd");
        File.WriteAllText(wait,
            "@echo off\r\n" +
            "timeout /t 2 /nobreak >nul\r\n" +
            "rmdir /s /q \"" + folder.Replace("\"", "\\\"") + "\"\r\n" +
            "del \"%~f0\"\r\n");
        // Started and not waited for: this script removes the folder that contains the file it
        // was launched from, so waiting here would mean deleting a running executable.
        Detach("cmd", "/c", wait);

        if (!silent)
        {
            MessageBox.Show(AppName + " удалён.", AppName,
                MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        return 0;
    }

    private static IEnumerable<Process> SafeProcesses(string name)
    {
        try { return Process.GetProcessesByName(name); }
        catch (Exception) { return new Process[0]; }
    }

    private static void Delete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); }
        catch (Exception) { /* a locked shortcut is not worth failing the uninstall over */ }
    }

    // --- small helpers -------------------------------------------------------------

    /** Reads an option written either as "--dir value" or as "--dir=value". */
    private static string ReadOption(string[] args, string name)
    {
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i].Equals(name, StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length)
            {
                return args[i + 1];
            }
            string prefix = name + "=";
            if (args[i].StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                return args[i].Substring(prefix.Length).Trim('"');
            }
        }
        return null;
    }

    private static void Run(string file, params string[] arguments)
    {
        Process child = Start(file, arguments);
        if (child == null) return;
        try { child.WaitForExit(10000); }
        finally { child.Dispose(); }
    }

    /** Starts a process and returns immediately, leaving it to finish on its own. */
    private static void Detach(string file, params string[] arguments)
    {
        Process child = Start(file, arguments);
        if (child != null) child.Dispose();
    }

    private static Process Start(string file, string[] arguments)
    {
        try
        {
            ProcessStartInfo start = new ProcessStartInfo
            {
                FileName = file,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            start.Arguments = string.Join(" ", arguments);
            return Process.Start(start);
        }
        catch (Exception e)
        {
            Trace.WriteLine(e.Message);
            return null;
        }
    }
}