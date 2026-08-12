using System.Runtime.InteropServices;

namespace FitGenerator.Native.Core;

internal interface INativeCoreApi
{
    uint ApiVersion { get; }

    byte[] Preview(byte[] request);

    byte[] GenerateFit(byte[] request);
}

internal sealed class NativeCoreApi : INativeCoreApi
{
    public uint ApiVersion => NativeMethods.GetApiVersion();

    public byte[] Preview(byte[] request) => NativeMethods.Preview(request);

    public byte[] GenerateFit(byte[] request) => NativeMethods.GenerateFit(request);
}

internal static class NativeMethods
{
    private const string LibraryName = "fit_generator_core";
    private const int InternalErrorCode = 900;
    private const string InternalErrorMessage = "核心发生内部错误";

    private delegate NativeResultHandle NativeOperation(byte[] request, nuint length);

    internal static uint GetApiVersion() => fg_core_api_version();

    internal static byte[] Preview(byte[] request) => Invoke(request, fg_preview);

    internal static byte[] GenerateFit(byte[] request) => Invoke(request, fg_generate_fit);

    internal static void FreeResult(NativeResultHandle result) =>
        fg_result_free(result.DangerousGetHandle());

    private static byte[] Invoke(byte[] request, NativeOperation operation)
    {
        ArgumentNullException.ThrowIfNull(request);

        using var result = operation(request, checked((nuint)request.LongLength));
        if (result is null || result.IsInvalid)
        {
            throw new NativeCoreException(InternalErrorCode, InternalErrorMessage);
        }

        var code = fg_result_code(result);
        if (code != 0)
        {
            var errorPointer = fg_result_error(result);
            var message = errorPointer == IntPtr.Zero
                ? InternalErrorMessage
                : Marshal.PtrToStringUTF8(errorPointer);
            throw new NativeCoreException(
                code,
                string.IsNullOrEmpty(message) ? InternalErrorMessage : message);
        }

        var lengthValue = fg_result_length(result);
        if (lengthValue == 0)
        {
            return Array.Empty<byte>();
        }

        if (lengthValue > (nuint)int.MaxValue)
        {
            throw new NativeCoreException(InternalErrorCode, "Rust 核心返回的数据过大");
        }

        var dataPointer = fg_result_data(result);
        if (dataPointer == IntPtr.Zero)
        {
            throw new NativeCoreException(InternalErrorCode, InternalErrorMessage);
        }

        var length = checked((int)lengthValue);
        var data = GC.AllocateUninitializedArray<byte>(length);
        Marshal.Copy(dataPointer, data, 0, length);
        return data;
    }

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern NativeResultHandle fg_preview(
        [In] byte[] request,
        nuint length);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern NativeResultHandle fg_generate_fit(
        [In] byte[] request,
        nuint length);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern IntPtr fg_result_data(NativeResultHandle result);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern nuint fg_result_length(NativeResultHandle result);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern int fg_result_code(NativeResultHandle result);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern IntPtr fg_result_error(NativeResultHandle result);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern void fg_result_free(IntPtr result);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern uint fg_core_api_version();
}
