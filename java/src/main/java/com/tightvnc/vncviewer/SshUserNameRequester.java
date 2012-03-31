//
//  Copyright (C) 2010 GlavSoft, LLC.  All Rights Reserved.
//
//  This is free software; you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation; either version 2 of the License, or
//  (at your option) any later version.
//
//  This software is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this software; if not, write to the Free Software
//  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
//  USA.
//

package com.tightvnc.vncviewer;

import javax.swing.SwingUtilities;

/**
 * This class requests a user name for SSH authentication using JDialog which
 * must be created and operated in the event dispatch thread.
 *
 * @author const
 */
public class SshUserNameRequester {

  /**
   * Query SSH user name.
   * @return user name or null if the dialog was canceled by the user.
   * @throws Exception if there was an error.
   */
  public String queryUserName() throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      new DialogRunner().run();
    } else { // not in the event dispatch thread
      SwingUtilities.invokeAndWait(new DialogRunner());
    }
    if (user == null) {
      throw new Exception("No user name entered for SSH authentication");
    }
    return user;
  }

  /**
   * Private inner class to implement a Runnable object to be executed in the
   * event dispatch thread.
   */
  private class DialogRunner implements Runnable {
    public void run() {
      user = null;
      if (!SwingUtilities.isEventDispatchThread()) {
        return; // prevent wrong usage
      }
      SshUserNameDialog dialog = new SshUserNameDialog(null, true);
      dialog.setVisible(true);
      int result = dialog.getReturnStatus();
      if (result == SshUserNameDialog.RET_OK) {
        user = dialog.getUserName();
      }
    }
  }

  private String user;
}
