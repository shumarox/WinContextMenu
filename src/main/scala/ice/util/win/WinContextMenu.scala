package ice.util.win

import java.awt.Toolkit
import java.awt.datatransfer.{DataFlavor, Transferable, UnsupportedFlavorException}
import java.io.File
import java.util

import com.sun.jna.platform.win32.COM.{COMUtils, IShellFolder}
import com.sun.jna.platform.win32.Guid.{IID, REFIID}
import com.sun.jna.platform.win32.ShellAPI.SHELLEXECUTEINFO
import com.sun.jna.platform.win32.WinDef.{HMENU, HWND, RECT, UINT_PTR}
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.platform.win32._
import com.sun.jna.ptr.{IntByReference, PointerByReference}
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.{Function, Library, Native, Pointer, WString}

import scala.collection.mutable

case class ExtraMenuInfo(label: String, function: () => Unit, subMenus: collection.Seq[ExtraMenuInfo] = Nil)

object WinContextMenu {

  var additionalRemoveList: collection.Seq[String] = Nil

  def main(args: Array[String]): Unit = {
    val files = if (args == null || args.isEmpty) Array(new File(".").getCanonicalPath) else args
    show(files.map(new File(_)), 0, 0)
  }

  private trait User32ForMenu extends Library {
    def CreatePopupMenu: HMENU

    def DestroyMenu(hmenu: HMENU): Long

    def InsertMenu(hMenu: HMENU, uPosition: Int, uFlags: Int, uIDNewItem: UINT_PTR, lpNewItem: String): Boolean

    def TrackPopupMenu(hMenu: HMENU, uFlags: Int, x: Int, y: Int, nReserved: Int, hWnd: HWND, prcRect: RECT): Long

    def GetMenuItemCount(hMenu: HMENU): Int

    def GetMenuItemID(hMenu: HMENU, nPos: Int): Int

    def GetMenuString(hMenu: HMENU, uIDItem: Int, buffer: Array[Char], nMaxCount: Int, uFlag: Int): Int

    def GetSubMenu(hMenu: HMENU, nPos: Int): HMENU

    def DeleteMenu(hmenu: HMENU, uPosition: Int, uFlags: Int): Boolean
  }

  private val User32ForMenu: User32ForMenu = Native.load("user32", classOf[User32ForMenu], W32APIOptions.DEFAULT_OPTIONS)

  private def executeAndThrow(name: String, f: => HRESULT): Unit = {
    val hResult = f
    if (!COMUtils.SUCCEEDED(hResult)) throw new IllegalStateException(s"$name failed. $hResult ${Kernel32Util.formatMessageFromLastErrorCode(hResult.intValue)}")
  }

  private def DoEvents(): Unit = {
    val msg = new MSG()
    while (User32.INSTANCE.PeekMessage(msg, null, 0, 0, 1)) {
      User32.INSTANCE.TranslateMessage(msg)
      User32.INSTANCE.DispatchMessage(msg)
    }
  }

  def show(files: collection.Seq[File], x: Int, y: Int, extraMenus: collection.Seq[ExtraMenuInfo] = Nil): Boolean = {
    // イベント消化（ポップアップメニューが表示されない現象を抑制）
    DoEvents()

    val dummyHwnd: HWND = User32Util.createWindow("Message", null, 0, 0, 0, 0, 0, null, null, null, null)

    User32.INSTANCE.SetForegroundWindow(dummyHwnd)

    try {
      val hResult = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED | Ole32.COINIT_DISABLE_OLE1DDE)
      if (!COMUtils.SUCCEEDED(hResult)) {
        Ole32.INSTANCE.CoUninitialize()
        executeAndThrow("CoInitiailzeEx", Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED | Ole32.COINIT_DISABLE_OLE1DDE))
      }

      val desktopShellFolder = {
        val desktopFolder = new PointerByReference
        executeAndThrow("SHGetDesktopFolder", Shell32.INSTANCE.SHGetDesktopFolder(desktopFolder))
        MyIShellFolder.Converter.PointerToIShellFolder(desktopFolder)
      }

      // Win32ShellFolderのインスタンスの場合は、getParentでPC(マイコンピュータ)などが取得されて処理できないため、Fileに変換が必要
      val firstFile = new File(files.head.getAbsolutePath)

      val (cpidls, shellFolder) =
        if (firstFile.getParent == null) {
          val pidls =
            files.map(f => new File(f.getAbsolutePath)).map { file =>
              val pidl = new PointerByReference
              executeAndThrow("ParseDisplayName", desktopShellFolder.ParseDisplayNameFromWString(dummyHwnd, null, new WString(file.getAbsolutePath), new IntByReference(), pidl, new IntByReference()))

              val pShellFolder = new PointerByReference
              executeAndThrow("SHBindToParent", desktopShellFolder.BindToObject(pidl.getValue, null, new REFIID(IShellFolder.IID_ISHELLFOLDER), pShellFolder))

              pidl
            }.toArray

          (pidls, desktopShellFolder)
        } else {
          var parent: File = firstFile.getParentFile

          while (parent != null && files.exists(f => parent.toPath.relativize(f.toPath).toFile.getPath.contains(".."))) {
            parent = parent.getParentFile
          }

          val shellFolder = {
            val pidl = new PointerByReference
            executeAndThrow("ParseDisplayName(parent)", desktopShellFolder.ParseDisplayNameFromWString(dummyHwnd, null, new WString(parent.getPath), new IntByReference(), pidl, new IntByReference()))

            val pShellFolder = new PointerByReference
            executeAndThrow("SHBindToParent", desktopShellFolder.BindToObject(pidl.getValue, null, new REFIID(IShellFolder.IID_ISHELLFOLDER), pShellFolder))

            MyIShellFolder.Converter.PointerToIShellFolder(pShellFolder)
          }

          desktopShellFolder.Release

          val pidls =
            files.map(f => new File(f.getAbsolutePath)).map { file =>
              val pidl = new PointerByReference
              executeAndThrow("ParseDisplayName(name)", shellFolder.ParseDisplayNameFromWString(dummyHwnd, null, new WString(parent.toPath.relativize(file.toPath).toFile.getPath), new IntByReference(), pidl, new IntByReference()))
              pidl
            }.toArray

          (pidls, shellFolder)
        }

      val contextMenu = {
        val pContextMenu = new PointerByReference
        executeAndThrow("GetUIObjectOf", shellFolder.GetUIObjectOfFromArray(dummyHwnd, cpidls.length, cpidls.map(_.getValue), new Guid.REFIID(new IID("000214e4-0000-0000-c000-000000000046")), null, pContextMenu))

        MyIContextMenu.Converter.PointerToIContextMenu(pContextMenu)
      }

      shellFolder.Release

      val menu = User32ForMenu.CreatePopupMenu

      executeAndThrow("QueryContextMenu", contextMenu.QueryContextMenu(menu, 2, 1, 0x7FFF, 4))

      val removeList: List[String] = List("アクセスを許可する(&G)", "送る(&N)", "切り取り(&T)", "ショートカットの作成(&S)", "以前のバージョンの復元(&V)")

      var copyMenuId: Int = -1
      var propertyMenuId: Int = -1

      val name = new Array[Char](1024)

      // メニュー走査1回目は「ライブラリを取得中...」となるものがあるため、2回まわす。
      (0 until 2).foreach { x =>
        (0 until 0x7FFF).foreach { i =>
          util.Arrays.fill(name, '\u0000')

          User32ForMenu.GetMenuString(menu, i, name, name.length, 0)

          val menuItemName = new String(name).takeWhile(_ != '\u0000')

          if (menuItemName.isEmpty) {
            // nop
          } else if (menuItemName == "コピー(&C)") {
            copyMenuId = i
          } else if (menuItemName == "プロパティ(&R)") {
            propertyMenuId = i
          } else if (removeList.contains(menuItemName) || additionalRemoveList.contains(menuItemName)) {
            User32ForMenu.DeleteMenu(menu, i, 0)
          }
        }
      }

      var extraMenuId: Long = 0x8000
      val extraMenuMap: mutable.HashMap[Long, () => Unit] = mutable.HashMap[Long, () => Unit]()

      val subMenusForDestroy: mutable.ArrayBuffer[HMENU] = mutable.ArrayBuffer[HMENU]()

      if (extraMenus.nonEmpty) {
        extraMenus.foreach { extraMenu =>
          if (extraMenu.subMenus.nonEmpty) {
            val subMenu = User32ForMenu.CreatePopupMenu
            subMenusForDestroy += subMenu
            val subMenuRef = new UINT_PTR(Pointer.nativeValue(subMenu.getPointer))

            extraMenu.subMenus.foreach { extraSubMenu =>
              val childMenu = new UINT_PTR(extraMenuId)
              User32ForMenu.InsertMenu(subMenu, 0, 0, childMenu, extraSubMenu.label)
              extraMenuMap.put(extraMenuId, extraSubMenu.function)
              extraMenuId += 1
            }

            User32ForMenu.InsertMenu(menu, propertyMenuId, 0x10, subMenuRef, extraMenu.label)
          } else {
            User32ForMenu.InsertMenu(menu, propertyMenuId, 0, new UINT_PTR(extraMenuId), extraMenu.label)
            extraMenuMap.put(extraMenuId, extraMenu.function)
            extraMenuId += 1
          }
        }

        User32ForMenu.InsertMenu(menu, propertyMenuId, 0x800, null, null)
      }

      val command = User32ForMenu.TrackPopupMenu(menu, 0x100, x, y, 0, dummyHwnd, null)

      subMenusForDestroy.foreach(User32ForMenu.DestroyMenu)
      User32ForMenu.DestroyMenu(menu)

      User32.INSTANCE.PostMessage(dummyHwnd, 0, null, null)

      val result =
        if (command == 0) {
          false
        } else {
          if (command >= 0x8000) {
            extraMenuMap(command).apply()
            false
          } else if (command == copyMenuId) {
            Toolkit.getDefaultToolkit.getSystemClipboard.setContents(new Transferable {
              override def getTransferDataFlavors: Array[DataFlavor] = Array[DataFlavor](DataFlavor.javaFileListFlavor)

              override def isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

              override def getTransferData(flavor: DataFlavor): Any =
                if (isDataFlavorSupported(flavor)) java.util.Arrays.asList(files.toArray: _*) else throw new UnsupportedFlavorException(flavor)
            }, null)
            true
          } else if (command == propertyMenuId) {
            files.map(_.getAbsolutePath).foreach { path =>
              val shellExecuteInfo =
                new SHELLEXECUTEINFO {
                  cbSize = Native.getNativeSize(classOf[SHELLEXECUTEINFO], null)
                  fMask = 0x40C
                  hwnd = dummyHwnd
                  lpVerb = "properties"
                  lpFile = path
                }
              Shell32.INSTANCE.ShellExecuteEx(shellExecuteInfo)
            }
            true
          } else {
            val commandInfo =
              new CMINVOKECOMMANDINFOEX() {
                cbSize = Native.getNativeSize(classOf[CMINVOKECOMMANDINFOEX], null)
                fMask = 0x10000
                hwnd = dummyHwnd
                lpVerb = (command - 1).asInstanceOf[Int]
                nShow = 10
              }

            executeAndThrow("InvokeCommand", contextMenu.InvokeCommand(commandInfo))
            true
          }
        }

      contextMenu.Release

      result
    } finally {
      Ole32.INSTANCE.CoUninitialize()

      User32Util.destroyWindow(dummyHwnd)
    }
  }

}

private trait MyIShellFolder extends IShellFolder {
  def ParseDisplayNameFromWString(hwnd: WinDef.HWND, pbc: Pointer, pszDisplayName: WString, pchEaten: IntByReference, ppidl: PointerByReference, pdwAttributes: IntByReference): WinNT.HRESULT

  def GetUIObjectOfFromArray(hwndOwner: WinDef.HWND, cidl: Int, apidl: Array[Pointer], riid: Guid.REFIID, rgfReserved: IntByReference, ppv: PointerByReference): WinNT.HRESULT
}

private object MyIShellFolder {

  object Converter {
    def PointerToIShellFolder(ptr: PointerByReference): MyIShellFolder = {
      val interfacePointer = ptr.getValue
      val vTablePointer = interfacePointer.getPointer(0)
      val vTable = new Array[Pointer](13)
      vTablePointer.read(0, vTable, 0, 13)

      new MyIShellFolder {
        override def QueryInterface(byValue: Guid.REFIID, pointerByReference: PointerByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(0), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, byValue, pointerByReference)))
        }

        override def AddRef: Int = {
          val f = Function.getFunction(vTable(1), Function.ALT_CONVENTION)
          f.invokeInt(Array[AnyRef](interfacePointer))
        }

        override def Release: Int = {
          val f = Function.getFunction(vTable(2), Function.ALT_CONVENTION)
          f.invokeInt(Array[AnyRef](interfacePointer))
        }

        override def ParseDisplayName(hwnd: WinDef.HWND, pbc: Pointer, pszDisplayName: String, pchEaten: IntByReference, ppidl: PointerByReference, pdwAttributes: IntByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(3), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, hwnd, pbc, pszDisplayName, pchEaten, ppidl, pdwAttributes)))
        }

        override def ParseDisplayNameFromWString(hwnd: WinDef.HWND, pbc: Pointer, pszDisplayName: WString, pchEaten: IntByReference, ppidl: PointerByReference, pdwAttributes: IntByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(3), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, hwnd, pbc, pszDisplayName, pchEaten, ppidl, pdwAttributes)))
        }

        override def EnumObjects(hwnd: WinDef.HWND, grfFlags: Int, ppenumIDList: PointerByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(4), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, hwnd, Integer.valueOf(grfFlags), ppenumIDList)))
        }

        override def BindToObject(pidl: Pointer, pbc: Pointer, riid: Guid.REFIID, ppv: PointerByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(5), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, pidl, pbc, riid, ppv)))
        }

        override def BindToStorage(pidl: Pointer, pbc: Pointer, riid: Guid.REFIID, ppv: PointerByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(6), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, pidl, pbc, riid, ppv)))
        }

        override def CompareIDs(lParam: WinDef.LPARAM, pidl1: Pointer, pidl2: Pointer): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(7), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, lParam, pidl1, pidl2)))
        }

        override def CreateViewObject(hwndOwner: WinDef.HWND, riid: Guid.REFIID, ppv: PointerByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(8), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, hwndOwner, riid, ppv)))
        }

        override def GetAttributesOf(cidl: Int, apidl: Pointer, rgfInOut: IntByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(9), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, Integer.valueOf(cidl), apidl, rgfInOut)))
        }

        override def GetUIObjectOf(hwndOwner: WinDef.HWND, cidl: Int, apidl: Pointer, riid: Guid.REFIID, rgfReserved: IntByReference, ppv: PointerByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(10), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, hwndOwner, Integer.valueOf(cidl), apidl, riid, rgfReserved, ppv)))
        }

        override def GetUIObjectOfFromArray(hwndOwner: WinDef.HWND, cidl: Int, apidl: Array[Pointer], riid: Guid.REFIID, rgfReserved: IntByReference, ppv: PointerByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(10), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, hwndOwner, Integer.valueOf(cidl), apidl, riid, rgfReserved, ppv)))
        }

        override def GetDisplayNameOf(pidl: Pointer, flags: Int, pName: ShTypes.STRRET): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(11), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, pidl, Integer.valueOf(flags), pName)))
        }

        override def SetNameOf(hwnd: WinDef.HWND, pidl: Pointer, pszName: String, uFlags: Int, ppidlOut: PointerByReference): WinNT.HRESULT = {
          val f = Function.getFunction(vTable(12), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, hwnd, pidl, pszName, Integer.valueOf(uFlags), ppidlOut)))
        }
      }
    }
  }

}

private trait MyIContextMenu {
  def Release: Int

  def QueryContextMenu(hmenu: HMENU, indexMenu: Int, idCmdFirst: Int, idCmdLast: Int, uFlags: Int): HRESULT

  def InvokeCommand(p: CMINVOKECOMMANDINFOEX): HRESULT
}

private object MyIContextMenu {

  object Converter {
    def PointerToIContextMenu(ptr: PointerByReference): MyIContextMenu = {
      val interfacePointer = ptr.getValue
      val vTablePointer = interfacePointer.getPointer(0)
      val vTable = new Array[Pointer](8)
      vTablePointer.read(0, vTable, 0, 8)

      new MyIContextMenu {
        override def Release: Int = {
          val f = Function.getFunction(vTable(2), Function.ALT_CONVENTION)
          f.invokeInt(Array[AnyRef](interfacePointer))
        }

        override def QueryContextMenu(hMenu: HMENU, indexMenu: Int, idCmdFirst: Int, idCmdLast: Int, flags: Int): HRESULT = {
          val f = Function.getFunction(vTable(3), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, hMenu, Integer.valueOf(indexMenu), Integer.valueOf(idCmdFirst), Integer.valueOf(idCmdLast), Integer.valueOf(flags))))
        }

        override def InvokeCommand(commandInfo: CMINVOKECOMMANDINFOEX): HRESULT = {
          val f = Function.getFunction(vTable(4), Function.ALT_CONVENTION)
          new WinNT.HRESULT(f.invokeInt(Array[AnyRef](interfacePointer, commandInfo)))
        }
      }
    }
  }

}
