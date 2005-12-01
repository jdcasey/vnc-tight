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
 */

// -=- ViewerToolBar.h

// ToolBar for the Vnc Viewer

#include <rfb_win32/ToolBar.h>

using namespace rfb::win32;

class ViewerToolBar : public ToolBar {
public:
  ViewerToolBar() {}
  ~ViewerToolBar() {}

  void create(HWND parentHwnd);

  LRESULT processWM_NOTIFY(WPARAM wParam, LPARAM lParam);

  void show();
  void hide();
};
