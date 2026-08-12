using Microsoft.Win32.SafeHandles;

namespace FitGenerator.Native.Core;

internal sealed class NativeResultHandle : SafeHandleZeroOrMinusOneIsInvalid
{
    public NativeResultHandle()
        : base(ownsHandle: true)
    {
    }

    protected override bool ReleaseHandle()
    {
        NativeMethods.FreeResult(this);
        return true;
    }
}
