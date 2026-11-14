// Windows launcher for the onmonit test stand-ins. StandIns substitutes the placeholders
// (as C# verbatim-string content) and compiles it with PowerShell Add-Type.
using System;
using System.Diagnostics;
using System.Text;

public static class StandInLauncher
{
    private const string Java = @"@JAVA@";
    private const string ClassPath = @"@CP@";
    private const string Role = @"@ROLE@";

    public static int Main(string[] args)
    {
        StringBuilder cmd = new StringBuilder();
        cmd.Append("-cp ").Append(Quote(ClassPath));
        cmd.Append(" -Donmonit.standin.wrapped=true");
        cmd.Append(" io.jenkins.plugins.onmonit.testing.StandIn ").Append(Role);
        foreach (string arg in args)
        {
            cmd.Append(' ').Append(Quote(arg));
        }
        ProcessStartInfo psi = new ProcessStartInfo(Java, cmd.ToString());
        psi.UseShellExecute = false;
        using (Process p = Process.Start(psi))
        {
            p.WaitForExit();
            return p.ExitCode;
        }
    }

    // Standard Windows command-line quoting (as parsed by CommandLineToArgvW / the MSVC runtime).
    private static string Quote(string arg)
    {
        if (arg.Length > 0 && arg.IndexOfAny(new char[] { ' ', '\t', '\n', '\v', '"' }) < 0)
        {
            return arg;
        }
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        foreach (char c in arg)
        {
            if (c == '\\')
            {
                backslashes++;
            }
            else if (c == '"')
            {
                sb.Append('\\', backslashes * 2 + 1).Append('"');
                backslashes = 0;
            }
            else
            {
                sb.Append('\\', backslashes).Append(c);
                backslashes = 0;
            }
        }
        sb.Append('\\', backslashes * 2).Append('"');
        return sb.ToString();
    }
}
