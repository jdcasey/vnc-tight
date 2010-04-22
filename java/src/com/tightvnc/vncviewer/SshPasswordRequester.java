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
 * This class requests a password for SSH authentication using JDialog which
 * must be created and operated in the event dispatch thread.
 *
 * @author const
 */
public class SshPasswordRequester {

  /**
   * The constructor.
   * @param message text that will be displayed near the password field.
   */
  public SshPasswordRequester(String message) {
    this.message = message;
    password = null;
  }

  /**
   * Query SSH password.
   * @return user name or null if the dialog was canceled by the user.
   * @throws Exception if there was an error.
   */
  public char[] queryPassword() throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      new DialogRunner().run();
    } else { // not in the event dispatch thread
      SwingUtilities.invokeAndWait(new DialogRunner());
    }
    if (password == null) {
      throw new Exception("No user name entered for SSH authentication");
    }
    char[] localReference = password;
    password = null;
    return localReference;
  }

  /**
   * Private inner class to implement a Runnable object to be executed in the
   * event dispatch thread.
   */
  private class DialogRunner implements Runnable {
    public void run() {
      password = null;
      if (!SwingUtilities.isEventDispatchThread()) {
        return; // prevent wrong usage
      }
      SshPasswordDialog dialog = new SshPasswordDialog(null, true, message);
      dialog.setVisible(true);
      int result = dialog.getReturnStatus();
      if (result == SshUserNameDialog.RET_OK) {
        password = dialog.getPassword();
      }
    }
  }

  private final String message;
  private char[] password;
}
