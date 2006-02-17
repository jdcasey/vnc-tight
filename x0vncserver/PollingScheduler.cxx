/* Copyright (C) 2006 Constantin Kaplinsky.  All Rights Reserved.
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

//
// PollingScheduler class implementation.
//

#include <string.h>
#include <stdlib.h>

#include <x0vncserver/PollingScheduler.h>

PollingScheduler::PollingScheduler(int interval, int maxload)
{
  setParameters(interval, maxload);
  reset();
}

void PollingScheduler::setParameters(int interval, int maxload)
{
  m_interval = interval;
  m_maxload = maxload;

  if (m_interval < 0) {
    m_interval = 0;
  }
  if (m_maxload < 1) {
    m_maxload = 1;
  } else if (m_maxload > 100) {
    m_maxload = 100;
  }
}

void PollingScheduler::reset()
{
  m_initialState = true;
}

void PollingScheduler::newPass()
{
  TimeMillis timeNow;

  if (m_initialState) {

    // First polling pass: initialize statistics.
    m_initialState = false;
    m_ratedDuration = 0;
    m_sleeping = 0;
    memset(m_errors, 0, sizeof(m_errors));
    m_errorSum = 0;
    m_errorAbsSum = 0;
    memset(m_durations, 0, sizeof(m_durations));
    m_durationSum = 0;
    memset(m_slept, 0, sizeof(m_slept));
    m_sleptSum = 0;
    m_idx = 0;

  } else {

    // Stop sleeping if not yet.
    if (m_sleeping)
      sleepFinished();

    // Update statistics on sleeping time and total pass duration.
    int duration = timeNow.diffFrom(m_passStarted);

    int oldest = m_durations[m_idx];
    m_durations[m_idx] = duration;
    m_durationSum = m_durationSum - oldest + duration;

    oldest = m_slept[m_idx];
    m_slept[m_idx] = m_sleptThisPass;
    m_sleptSum = m_sleptSum - oldest + m_sleptThisPass;

    // Compute and save the difference between actual and planned time.
    int newError = duration - m_interval;
    oldest = m_errors[m_idx];
    m_errors[m_idx] = newError;
    m_errorSum = m_errorSum - oldest + newError;
    m_errorAbsSum = m_errorAbsSum - abs(oldest) + abs(newError);

    //
    // Here is the most important part.
    // Compute desired duration of the upcoming polling pass.
    //
    m_ratedDuration = m_interval - m_errorSum;

    int optimalLoadDuration =
      ((m_durationSum - m_sleptSum) * 900 + m_maxload * 4) / (m_maxload * 8)
      - m_durationSum;

    if (m_ratedDuration < optimalLoadDuration) {
      m_ratedDuration = optimalLoadDuration;
    }

    if (m_ratedDuration < 0) {
      m_ratedDuration = 0;
    }

    // Update ring buffer indexer (8 elements in the arrays).
    m_idx = (m_idx + 1) & 7;

  }

  m_passStarted = timeNow;
  m_sleptThisPass = 0;
}

void PollingScheduler::sleepStarted()
{
  if (m_initialState || m_sleeping)
    return;

  m_sleepStarted.update();

  m_sleeping = true;
}

void PollingScheduler::sleepFinished()
{
  if (m_initialState || !m_sleeping)
    return;

  TimeMillis timeNow;
  m_sleptThisPass += timeNow.diffFrom(m_sleepStarted);

  m_sleeping = false;
}

int PollingScheduler::millisRemaining() const
{
  if (m_initialState)
    return 0;

  TimeMillis timeNow;
  int elapsed = timeNow.diffFrom(m_passStarted);

  if (elapsed > m_ratedDuration)
    return 0;

  return (m_ratedDuration - elapsed);
}

bool PollingScheduler::goodTimeToPoll() const
{
  if (m_initialState)
    return true;

  // Average error (per 8 elements in the ring buffer).
  int errorAvg = (m_errorAbsSum + 4) / 8;

  // It's ok to poll earlier if new error is no more than half-average.
  return (millisRemaining() <= errorAvg / 2);
}
