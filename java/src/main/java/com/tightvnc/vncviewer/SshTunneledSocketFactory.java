//
//  Copyright (C) 2002 Constantin Kaplinsky.  All Rights Reserved.
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

//
// SshTunneledSocketFactory.java together with SshTunneledSocket.java
// implement an alternate way to connect to VNC servers via one or two
// HTTP proxies supporting the HTTP CONNECT method.
//

package com.tightvnc.vncviewer;

import com.jcraft.jsch.*;
import java.applet.*;
import java.awt.*;
import java.net.*;
import java.io.*;
import javax.swing.*;

class SshTunneledSocketFactory implements SocketFactory {

  public Socket createSocket(String host, int port, Applet applet)
          throws IOException {

    return createSocket(host, port, applet.getParameter("SSHHOST"));
  }

  public Socket createSocket(String host, int port, String[] args)
          throws IOException {

    return createSocket(host, port, readArg(args, "SSHHOST"));
  }

  public Socket createSocket(String host, int port,
                             String sshHost) throws IOException {
    if (localPort == 0) {
      if (sshHost == null) {
        sshHost = System.getProperty("user.name") + "@" + host;
        System.out.println("SSH host not specified, assuming " + sshHost);
      }
      System.out.println("Creating SSH tunnel to " + sshHost);
      try {
        createTunnel(host, port, sshHost);
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("Could not create SSH tunnel; cause: " + e.getMessage());
      }
      System.out.println("Local port for the SSH tunnel is " + localPort);
    }

    return new Socket("127.0.0.1", localPort);
  }

  private void createTunnel(String host, int port,
                            String sshHost) throws Exception {
    try {
      JSch jsch = new JSch();

      final int atIndex = sshHost.indexOf('@');
      String user = "";
      if (atIndex > 0) {
        user = sshHost.substring(0, atIndex);
      } else {
        user = new SshUserNameRequester().queryUserName();
      }

      // This should work correctly even if (atIndex == -1).
      sshHost = sshHost.substring(atIndex + 1);

      Session session = jsch.getSession(user, sshHost, 22);

      UserInfo ui = new MyUserInfo();
      session.setUserInfo(ui);

      session.connect();

      localPort = session.setPortForwardingL(localPort, host, port);
    } catch (JSchException e) {
      throw new IOException(e);
    }
  }

  private String readArg(String[] args, String name) {
    for (int i = 0; i < args.length; i += 2) {
      if (args[i].equalsIgnoreCase(name)) {
        try {
          return args[i + 1];
        } catch (Exception e) {
          return null;
        }
      }
    }
    return null;
  }
  /**
   * Local port assigned by setPortForwardingL(), or 0 if there is no active
   * SSH tunnel.
   */
  private int localPort = 0;

  public static class MyUserInfo implements UserInfo, UIKeyboardInteractive {

    public String getPassword() {
      return passwd;
    }

    public boolean promptYesNo(String str) {
      Object[] options = {"yes", "no"};
      int foo = JOptionPane.showOptionDialog(null,
                                             str,
                                             "SSH: Warning",
                                             JOptionPane.DEFAULT_OPTION,
                                             JOptionPane.WARNING_MESSAGE,
                                             null, options, options[0]);
      return foo == 0;
    }

    public String getPassphrase() {
      return null;
    }

    public boolean promptPassphrase(String message) {
      return true;
    }

    public boolean promptPassword(String message) {
      SshPasswordRequester requester = new SshPasswordRequester(message + ":");
      try {
        char[] passwdChars = requester.queryPassword();
        passwd = new String(passwdChars);
        java.util.Arrays.fill(passwdChars, '\0');
        return true;
      } catch (Exception e) {
        return false;
      }
    }

    public void showMessage(String message) {
      JOptionPane.showMessageDialog(null, message);
    }

    public String[] promptKeyboardInteractive(String destination,
                                              String name,
                                              String instruction,
                                              String[] prompt,
                                              boolean[] echo) {
      Container panel = new JPanel();
      panel.setLayout(new GridBagLayout());

      final GridBagConstraints gbc =
              new GridBagConstraints(0, 0, 1, 1, 1, 1,
                                     GridBagConstraints.NORTHWEST,
                                     GridBagConstraints.NONE,
                                     new Insets(0, 0, 0, 0), 0, 0);

      gbc.weightx = 1.0;
      gbc.gridwidth = GridBagConstraints.REMAINDER;
      gbc.gridx = 0;
      panel.add(new JLabel(instruction), gbc);
      gbc.gridy++;

      gbc.gridwidth = GridBagConstraints.RELATIVE;

      JTextField[] texts = new JTextField[prompt.length];
      for (int i = 0; i < prompt.length; i++) {
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridx = 0;
        gbc.weightx = 1;
        panel.add(new JLabel(prompt[i]), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weighty = 1;
        if (echo[i]) {
          texts[i] = new JTextField(20);
        } else {
          texts[i] = new JPasswordField(20);
        }
        panel.add(texts[i], gbc);
        gbc.gridy++;
      }

      if (JOptionPane.showConfirmDialog(null, panel,
                                        destination + ": " + name,
                                        JOptionPane.OK_CANCEL_OPTION,
                                        JOptionPane.QUESTION_MESSAGE)
              == JOptionPane.OK_OPTION) {
        String[] response = new String[prompt.length];
        for (int i = 0; i < prompt.length; i++) {
          response[i] = texts[i].getText();
        }
        return response;
      } else {
        return null;  // cancel
      }
    }

    private String passwd;
  }
}

