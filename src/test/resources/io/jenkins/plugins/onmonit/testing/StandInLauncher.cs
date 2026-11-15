// Windows launcher for the onmonit test stand-ins. StandIns substitutes the placeholders
// (as C# verbatim-string content) and compiles it with PowerShell Add-Type.
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

public static class StandInLauncher
{
    private const string Java = @"@JAVA@";
    private const string ClassPath = @"@CP@";
    private const string Role = @"@ROLE@";

    private const int JobObjectExtendedLimitInformation = 9;
    private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;

    // Never closed explicitly: the handle closes when this launcher dies, however it dies, and the job then
    // kills the stand-in JVM (KILL_ON_JOB_CLOSE), like a real single-process binary would disappear.
    private static IntPtr job = IntPtr.Zero;

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
            BindToJob(p);
            p.WaitForExit();
            return p.ExitCode;
        }
    }

    // Fails open: without a job object the stand-in still runs, it only may outlive a killed launcher.
    private static void BindToJob(Process p)
    {
        job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero)
        {
            Warn("CreateJobObject");
            return;
        }
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, ref info,
                (uint) Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION))))
        {
            Warn("SetInformationJobObject");
            return;
        }
        if (!AssignProcessToJobObject(job, p.Handle))
        {
            Warn("AssignProcessToJobObject");
        }
    }

    private static void Warn(string call)
    {
        Console.Error.WriteLine("StandInLauncher: job object unavailable (" + call + " error "
                + Marshal.GetLastWin32Error() + "); child may outlive launcher");
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr attributes, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(IntPtr job, int infoClass,
            ref JOBOBJECT_EXTENDED_LIMIT_INFORMATION info, uint length);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
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
