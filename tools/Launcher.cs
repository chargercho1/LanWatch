using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

// A launcher that gives the JVM a DPI-aware process.
//
// Windows shrinks a window whose process never declared itself DPI aware, and the JVM has no way
// to declare it: sun.java2d.dpiaware and sun.java2d.uiScale do not restore the missing declaration
// on Windows, so a Swing window laid out for 1180 pixels is given 944 and the right-hand side of
// the interface is cut off. Declaring it in the executable's manifest is the supported way, and
// the JVM reads the process's awareness that is already set up when it starts.
//
// It does nothing else: java runs as a child process with the arguments passed through.

internal static class Launcher
{
    [STAThread]
    private static int Main(string[] args)
    {
        // Nothing here should paint; this exists to carry the manifest, not a window.
        Application.EnableVisualStyles();

        string java = FindJava();
        if (java == null)
        {
            MessageBox.Show(
                "Не найден Java.\n\nУстановите JDK 17 или задайте переменную JAVA_HOME.",
                "LanWatch", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return 2;
        }

        string jar = Path.Combine(AppContext.BaseDirectory, "LanWatch.jar");
        if (!File.Exists(jar))
        {
            MessageBox.Show(
                "Рядом с программой нет LanWatch.jar.\n\nСоберите его: bash build.sh",
                "LanWatch", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return 2;
        }

        ProcessStartInfo start = new ProcessStartInfo
        {
            FileName = java,
            UseShellExecute = false,
            WorkingDirectory = AppContext.BaseDirectory,
        };
        // ArgumentList does not exist in the .NET Framework compiler, so the line is quoted by
        // hand. The jar path can legitimately contain spaces, which is why it is quoted at all.
        start.Arguments = "-jar " + Quote(jar) + " " + Join(args);

        try
        {
            using (Process child = Process.Start(start))
            {
                child.WaitForExit();
                return child.ExitCode;
            }
        }
        catch (Exception e)
        {
            MessageBox.Show("Не удалось запустить Java:\n" + e.Message,
                "LanWatch", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 2;
        }
    }

    /** Quotes one argument for a command line, escaping the backslash before a closing quote. */
    private static string Quote(string value)
    {
        return "\"" + value.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";
    }

    private static string Join(string[] values)
    {
        System.Text.StringBuilder outp = new System.Text.StringBuilder();
        foreach (string value in values)
        {
            if (outp.Length > 0) outp.Append(' ');
            outp.Append(Quote(value));
        }
        return outp.ToString();
    }

    /** JAVA_HOME first, then the PATH, so a plain JDK install works with no setup at all. */
    private static string FindJava()
    {
        string home = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(home))
        {
            string candidate = Path.Combine(Path.Combine(home, "bin"), "javaw.exe");
            if (File.Exists(candidate)) return candidate;
            candidate = Path.Combine(Path.Combine(home, "bin"), "java.exe");
            if (File.Exists(candidate)) return candidate;
        }

        string path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (string directory in path.Split(';'))
        {
            if (directory.Length == 0) continue;
            try
            {
                string candidate = Path.Combine(directory.Trim(), "javaw.exe");
                if (File.Exists(candidate)) return candidate;
            }
            catch (ArgumentException)
            {
                // A malformed PATH entry is not worth failing over.
            }
        }
        return null;
    }
}