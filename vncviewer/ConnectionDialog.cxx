/* Copyright (C) 2002-2003 RealVNC Ltd.  All Rights Reserved.
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
#include <vncviewer/ConnectionDialog.h>
#include <vncviewer/CView.h>
#include <vncviewer/resource.h>

#include <tchar.h>

using namespace rfb;
using namespace rfb::win32;


ConnectionDialog::ConnectionDialog(CView* view_) : Dialog(GetModuleHandle(0)), view(view_) {
}


bool ConnectionDialog::showDialog() {
  return Dialog::showDialog(MAKEINTRESOURCE(IDD_CONNECTION_DLG));
}

void ConnectionDialog::initDialog() {
  HWND box = GetDlgItem(handle, IDC_SERVER_EDIT);

  std::list<char*> mru = MRU::getEntries();
  std::list<char*>::iterator i;

  // Locate the combo-box
  // NB: TCharArray converts the supplied char* and assumes ownership!
  for (i=mru.begin(); i!=mru.end(); i++) {
    int index = SendMessage(box, CB_ADDSTRING, 0, (LPARAM)TCharArray(*i).buf);
  }

  // Select the first item in the list
  SendMessage(box, CB_SETCURSEL, 0, 0);
}


bool ConnectionDialog::onOk() {
  delete [] hostname.buf;
  hostname.buf = 0;
  hostname.buf = getItemString(IDC_SERVER_EDIT);
  return hostname.buf[0] != 0;
}

bool ConnectionDialog::onCommand(int id, int cmd) {
  switch (id) {
  case IDC_ABOUT:
    AboutDialog::instance.showDialog();
    return true;
  case IDC_OPTIONS:
    view->optionsDialog.showDialog(view);
    return true;
  };
  return false;
}
