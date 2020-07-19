package ice.util.win

import com.sun.jna.platform.win32.{WinDef, WinNT}
import com.sun.jna.win32.W32APITypeMapper
import com.sun.jna.{Pointer, Structure}

class CMINVOKECOMMANDINFOEX(memory: Pointer) extends IStructure(memory, Structure.ALIGN_DEFAULT, W32APITypeMapper.UNICODE) {
  var cbSize = 0
  var fMask = 0
  var hwnd: WinDef.HWND = _
  var lpVerb = 0
  var lpParameters: String = _
  var lpDirectory: String = _
  var nShow = 0
  var dwHotKey = 0
  var hIcon: WinNT.HANDLE = _
  var lpTitle: String = _
  var lpVerbW: String = _
  var lpParametersW: String = _
  var lpDirectoryW: String = _
  var lpTitleW: String = _
  var ptInvoke: WinDef.POINT = _

  def this() = this(null)

  override protected def getFieldOrderList: List[String] =
    List("cbSize", "fMask", "hwnd", "lpVerb", "lpParameters", "lpDirectory", "nShow", "dwHotKey", "hIcon", "lpTitle", "lpVerbW", "lpParametersW", "lpDirectoryW", "lpTitleW", "ptInvoke")
}

