/* Copyright (C) 2004 TightVNC Team.  All Rights Reserved.
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
 */

// -=- OptionsDialog.h

#include <rfb_win32/Dialog.h>

#include <rfbplayer/PlayerOptions.h>

class OptionsDialog : public rfb::win32::Dialog {
public:
  OptionsDialog(PlayerOptions *_options) 
  : Dialog(GetModuleHandle(0)), options(_options), combo(0) {}
  // - Show the dialog and return true if OK was clicked,
  //   false in case of error or Cancel
  virtual bool showDialog() {
    return Dialog::showDialog(MAKEINTRESOURCE(IDD_OPTIONS));
  }
protected:

  // Dialog methods (protected)
  virtual void initDialog() {
    combo = GetDlgItem(handle, IDC_PIXELFORMAT);
    SendMessage(combo, CB_ADDSTRING, 0, (LPARAM)(LPCTSTR)("Auto"));
    SendMessage(combo, CB_ADDSTRING, 0, (LPARAM)(LPCTSTR)("8 bit depth (RGB332)"));
    SendMessage(combo, CB_ADDSTRING, 0, (LPARAM)(LPCTSTR)("16 bit depth (RGB655)"));
    SendMessage(combo, CB_ADDSTRING, 0, (LPARAM)(LPCTSTR)("24 bit depth (RGB888)"));
    SendMessage(combo, CB_SETCURSEL, options->pixelFormat, 0);
    if (options->askPixelFormat) {
      setItemChecked(IDC_ASK_PF, true);
      enableItem(IDC_PIXELFORMAT, false);
    }
    setItemChecked(IDC_ACCEPT_BELL, options->acceptBell);
    setItemChecked(IDC_ACCEPT_CUT_TEXT, options->acceptCutText);
    setItemChecked(IDC_AUTO_STORE_PARAM, options->autoStoreSettings);
    setItemChecked(IDC_AUTOPLAY, options->autoPlay);
  }
  virtual bool onOk() {
    if (!isItemChecked(IDC_ASK_PF)) {
      options->pixelFormat = SendMessage(combo, CB_GETCURSEL, 0, 0);
    }
    options->askPixelFormat = isItemChecked(IDC_ASK_PF);
    options->acceptBell = isItemChecked(IDC_ACCEPT_BELL);
    options->acceptCutText = isItemChecked(IDC_ACCEPT_CUT_TEXT);
    options->autoStoreSettings = isItemChecked(IDC_AUTO_STORE_PARAM);
    options->autoPlay = isItemChecked(IDC_AUTOPLAY);
    options->writeToRegistry();
    return true;
  }
  virtual bool onCommand(int item, int cmd) { 
    if (item == IDC_ASK_PF) {
      enableItem(IDC_PIXELFORMAT, !isItemChecked(IDC_ASK_PF));
    }
    if (item == IDC_DEFAULT) {
      SendMessage(combo, CB_SETCURSEL, DEFAULT_PF, 0);
      enableItem(IDC_PIXELFORMAT, !DEFAULT_ASK_PF);
      setItemChecked(IDC_ASK_PF, DEFAULT_ASK_PF);
      setItemChecked(IDC_ACCEPT_BELL, DEFAULT_ACCEPT_BELL);
      setItemChecked(IDC_ACCEPT_CUT_TEXT, DEFAULT_ACCEPT_CUT_TEXT);
      setItemChecked(IDC_AUTO_STORE_PARAM, DEFAULT_STORE_SETTINGS);
      setItemChecked(IDC_AUTOPLAY, DEFAULT_AUTOPLAY);
    }
    return false;
  }

  HWND combo;
  PlayerOptions *options;
};