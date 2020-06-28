package ice.util.win;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APITypeMapper;

import java.util.Arrays;
import java.util.List;

public class CMINVOKECOMMANDINFOEX extends Structure {
    public int cbSize;
    public int fMask;
    public WinDef.HWND hwnd;
    public int lpVerb;
    public String lpParameters;
    public String lpDirectory;
    public int nShow;
    public int dwHotKey;
    public WinNT.HANDLE hIcon;
    public String lpTitle;
    public String lpVerbW;
    public String lpParametersW;
    public String lpDirectoryW;
    public String lpTitleW;
    public WinDef.POINT ptInvoke;

    public CMINVOKECOMMANDINFOEX() {
        this(null);
    }

    public CMINVOKECOMMANDINFOEX(Pointer memory) {
        super(memory, Structure.ALIGN_DEFAULT, W32APITypeMapper.UNICODE);
    }

    protected List<String> getFieldOrder() {
        return Arrays.asList("cbSize", "fMask", "hwnd", "lpVerb", "lpParameters", "lpDirectory", "nShow", "dwHotKey", "hIcon", "lpTitle", "lpVerbW", "lpParametersW", "lpDirectoryW", "lpTitleW", "ptInvoke");
    }
}

