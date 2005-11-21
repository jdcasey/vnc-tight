/* Copyright (C) 2005 TightVNC Team.  All Rights Reserved.
 *    
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 *
 * TightVNC distribution homepage on the Web: http://www.tightvnc.com/
 *
 */

// -=- FTDialog.h

#ifndef __RFB_WIN32_FTDIALOG_H__
#define __RFB_WIN32_FTDIALOG_H__

#include <windows.h>
#include <commctrl.h>

#include <rfb/FileInfo.h>
#include <vncviewer/FileTransfer.h>
#include <vncviewer/FTListView.h>
#include <vncviewer/FTProgress.h>
#include <vncviewer/resource.h>

namespace rfb {
  namespace win32 {
    class FileTransfer;

    class FTDialog
    {
    public:
      FTDialog(HINSTANCE hInst, FileTransfer *pFT);
      ~FTDialog();
      
      bool createFTDialog(HWND hwndParent);
      bool closeFTDialog();
      void destroyFTDialog();
      
      static BOOL CALLBACK FTDialogProc(HWND hwnd, UINT uMsg, WPARAM wParam, LPARAM lParam);
      
      void addRemoteLVItems(FileInfo *pFI);
      void reqFolderUnavailable();
      
    private:
      FileTransfer *m_pFileTransfer;
      
      HWND m_hwndFTDialog;
      HWND m_hwndLocalPath;
      HWND m_hwndRemotePath;
      HINSTANCE m_hInstance;
      
      void showLocalLVItems();
      void showRemoteLVItems();

      void onLocalItemActivate(LPNMITEMACTIVATE lpnmia);
      void onRemoteItemActivate(LPNMITEMACTIVATE lpnmia);

      void onLocalReload();
      void onRemoteReload();

      void setIcon(int dest, int idIcon);
      bool initFTDialog();
      
      void onLocalOneUpFolder();
      void onRemoteOneUpFolder();
      int makeOneUpFolder(char *pPath);
      
      bool m_bDlgShown;

      FTListView *m_pLocalLV;
      FTListView *m_pRemoteLV;

      FTProgress *m_pProgress;

      char m_szLocalPath[FT_FILENAME_SIZE];
      char m_szRemotePath[FT_FILENAME_SIZE];
      char m_szLocalPathTmp[FT_FILENAME_SIZE];
      char m_szRemotePathTmp[FT_FILENAME_SIZE];
    };
  }
}

#endif // __RFB_WIN32_FTDIALOG_H__
