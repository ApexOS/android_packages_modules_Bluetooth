/******************************************************************************
 *
 *  Copyright 2009-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 *
 *  Filename:      bta_pan_co.c
 *
 *  Description:   PAN stack callout api
 *
 *
 ******************************************************************************/
#include "bta_pan_co.h"

#include <base/logging.h>
#include <hardware/bluetooth.h>
#include <hardware/bt_pan.h>
#include <string.h>

#include "os/log.h"
#include "bta_api.h"
#include "bta_pan_api.h"
#include "bta_pan_ci.h"
#include "btif_pan_internal.h"
#include "btif_sock_thread.h"
#include "btif_util.h"
#include "osi/include/allocator.h"
#include "osi/include/osi.h"
#include "pan_api.h"
#include "stack/include/bt_hdr.h"
#include "types/raw_address.h"

/*******************************************************************************
 *
 * Function         bta_pan_co_init
 *
 * Description
 *
 *
 * Returns          Data flow mask.
 *
 ******************************************************************************/
uint8_t bta_pan_co_init(uint8_t* q_level) {
  LOG_VERBOSE("bta_pan_co_init");

  /* set the q_level to 30 buffers */
  *q_level = 30;

  // return (BTA_PAN_RX_PULL | BTA_PAN_TX_PULL);
  return (BTA_PAN_RX_PUSH_BUF | BTA_PAN_RX_PUSH | BTA_PAN_TX_PULL);
}

/*******************************************************************************
 *
 * Function         bta_pan_co_close
 *
 * Description      This function is called by PAN when a connection to a
 *                  peer is closed.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_pan_co_close(uint16_t handle, uint8_t app_id) {
  LOG_VERBOSE("bta_pan_co_close:app_id:%d, handle:%d", app_id, handle);
  btpan_conn_t* conn = btpan_find_conn_handle(handle);
  if (conn && conn->state == PAN_STATE_OPEN) {
    LOG_VERBOSE("bta_pan_co_close");

    // let bta close event reset this handle as it needs
    // the handle to find the connection upon CLOSE
    // conn->handle = -1;
    conn->state = PAN_STATE_CLOSE;
    btpan_cb.open_count--;

    if (btpan_cb.open_count == 0 && btpan_cb.tap_fd != -1) {
      btpan_tap_close(btpan_cb.tap_fd);
      btpan_cb.tap_fd = -1;
    }
  }
}

/*******************************************************************************
 *
 * Function         bta_pan_co_tx_path
 *
 * Description      This function is called by PAN to transfer data on the
 *                  TX path; that is, data being sent from BTA to the phone.
 *                  This function is used when the TX data path is configured
 *                  to use the pull interface.  The implementation of this
 *                  function will typically call Bluetooth stack functions
 *                  PORT_Read() or PORT_ReadData() to read data from RFCOMM
 *                  and then a platform-specific function to send data that
 *                  data to the phone.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_pan_co_tx_path(uint16_t handle, uint8_t app_id) {
  BT_HDR* p_buf;
  RawAddress src;
  RawAddress dst;
  uint16_t protocol;
  bool ext;
  bool forward;

  LOG_VERBOSE("%s, handle:%d, app_id:%d", __func__, handle, app_id);

  btpan_conn_t* conn = btpan_find_conn_handle(handle);
  if (!conn) {
    LOG_ERROR("%s: cannot find pan connection", __func__);
    return;
  } else if (conn->state != PAN_STATE_OPEN) {
    LOG_ERROR("%s: conn is not opened, conn:%p, conn->state:%d", __func__, conn,
              conn->state);
    return;
  }

  do {
    /* read next data buffer from pan */
    p_buf = bta_pan_ci_readbuf(handle, src, dst, &protocol, &ext, &forward);
    if (p_buf) {
      LOG_VERBOSE(
          "%s, calling btapp_tap_send, "
          "p_buf->len:%d, offset:%d",
          __func__, p_buf->len, p_buf->offset);
      if (is_empty_eth_addr(conn->eth_addr) && is_valid_bt_eth_addr(src)) {
        VLOG(1) << __func__ << " pan bt peer addr: "
                << ADDRESS_TO_LOGGABLE_STR(conn->peer)
                << " update its ethernet addr: " << src;
        conn->eth_addr = src;
      }
      btpan_tap_send(btpan_cb.tap_fd, src, dst, protocol,
                     (char*)(p_buf + 1) + p_buf->offset, p_buf->len, ext,
                     forward);
      osi_free(p_buf);
    }

  } while (p_buf != NULL);
}

/*******************************************************************************
 *
 * Function         bta_pan_co_rx_path
 *
 * Description
 *
 *
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_pan_co_rx_path(UNUSED_ATTR uint16_t handle,
                        UNUSED_ATTR uint8_t app_id) {
  LOG_VERBOSE("bta_pan_co_rx_path not used");
}

/*******************************************************************************
 *
 * Function         bta_pan_co_rx_flow
 *
 * Description      This function is called by PAN to enable or disable
 *                  data flow on the RX path when it is configured to use
 *                  a push interface.  If data flow is disabled the phone must
 *                  not call bta_pan_ci_rx_write() or bta_pan_ci_rx_writebuf()
 *                  until data flow is enabled again.
 *
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_pan_co_rx_flow(UNUSED_ATTR uint16_t handle, UNUSED_ATTR uint8_t app_id,
                        UNUSED_ATTR bool enable) {
  LOG_VERBOSE("bta_pan_co_rx_flow, enabled:%d, not used", enable);
  btpan_conn_t* conn = btpan_find_conn_handle(handle);
  if (!conn || conn->state != PAN_STATE_OPEN) return;
  btpan_set_flow_control(enable);
}

/*******************************************************************************
 *
 * Function         bta_pan_co_filt_ind
 *
 * Description      protocol filter indication from peer device
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_pan_co_pfilt_ind(UNUSED_ATTR uint16_t handle,
                          UNUSED_ATTR bool indication,
                          UNUSED_ATTR tBTA_PAN_STATUS result,
                          UNUSED_ATTR uint16_t len,
                          UNUSED_ATTR uint8_t* p_filters) {
  LOG_VERBOSE("bta_pan_co_pfilt_ind");
}

/*******************************************************************************
 *
 * Function         bta_pan_co_mfilt_ind
 *
 * Description      multicast filter indication from peer device
 *
 * Returns          void
 *
 ******************************************************************************/
void bta_pan_co_mfilt_ind(UNUSED_ATTR uint16_t handle,
                          UNUSED_ATTR bool indication,
                          UNUSED_ATTR tBTA_PAN_STATUS result,
                          UNUSED_ATTR uint16_t len,
                          UNUSED_ATTR uint8_t* p_filters) {
  LOG_VERBOSE("bta_pan_co_mfilt_ind");
}
