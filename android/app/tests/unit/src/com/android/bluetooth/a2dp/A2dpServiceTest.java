/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.a2dp;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.SilenceDeviceManager;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.FakeFeatureFlagsImpl;
import com.android.bluetooth.flags.Flags;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class A2dpServiceTest {
    private static final int MAX_CONNECTED_AUDIO_DEVICES = 5;

    private BluetoothAdapter mAdapter;
    private Context mTargetContext;
    private A2dpService mA2dpService;
    private BluetoothDevice mTestDevice;
    private static final int TIMEOUT_MS = 1000;    // 1s

    private BroadcastReceiver mA2dpIntentReceiver;
    private final BlockingQueue<Intent> mConnectionStateChangedQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Intent> mAudioStateChangedQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Intent> mCodecConfigChangedQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Intent> mActiveDeviceQueue = new LinkedBlockingQueue<>();

    private FakeFeatureFlagsImpl mFakeFlagsImpl;
    @Mock private AdapterService mAdapterService;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private A2dpNativeInterface mMockNativeInterface;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private SilenceDeviceManager mSilenceDeviceManager;

    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getTargetContext();
        // Set up mocks and test assets
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        TestUtils.setAdapterService(mAdapterService);
        doReturn(true).when(mAdapterService).isA2dpOffloadEnabled();
        doReturn(MAX_CONNECTED_AUDIO_DEVICES).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(true, false).when(mAdapterService).isStartedProfile(anyString());
        doReturn(false).when(mAdapterService).isQuietModeEnabled();
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();
        doReturn(mSilenceDeviceManager).when(mAdapterService).getSilenceDeviceManager();

        mFakeFlagsImpl = new FakeFeatureFlagsImpl();
        mFakeFlagsImpl.setFlag(Flags.FLAG_AUDIO_ROUTING_CENTRALIZATION, false);
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mA2dpService = new A2dpService(mTargetContext, mMockNativeInterface, mFakeFlagsImpl);
        mA2dpService.doStart();

        // Override the timeout value to speed up the test
        A2dpStateMachine.sConnectTimeoutMs = TIMEOUT_MS;    // 1s

        // Set up the Connection State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        mA2dpIntentReceiver = new A2dpIntentReceiver();
        mTargetContext.registerReceiver(mA2dpIntentReceiver, filter);

        // Get a device for testing
        mTestDevice = mAdapter.getRemoteDevice("00:01:02:03:04:05");
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[]{BluetoothUuid.A2DP_SINK}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
    }

    @After
    public void tearDown() throws Exception {
        mA2dpService.doStop();
        mTargetContext.unregisterReceiver(mA2dpIntentReceiver);
        mConnectionStateChangedQueue.clear();
        mAudioStateChangedQueue.clear();
        mCodecConfigChangedQueue.clear();
        mActiveDeviceQueue.clear();
        TestUtils.clearAdapterService(mAdapterService);
    }

    private class A2dpIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
                try {
                    mConnectionStateChangedQueue.put(intent);
                } catch (InterruptedException e) {
                    Assert.fail("Cannot add Intent to the Connection State queue: "
                                + e.getMessage());
                }
            }
            if (BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED.equals(intent.getAction())) {
                try {
                    mAudioStateChangedQueue.put(intent);
                } catch (InterruptedException e) {
                    Assert.fail("Cannot add Intent to the Audio State queue: " + e.getMessage());
                }
            }
            if (BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED.equals(intent.getAction())) {
                try {
                    mCodecConfigChangedQueue.put(intent);
                } catch (InterruptedException e) {
                    Assert.fail("Cannot add Intent to the Codec Config queue: " + e.getMessage());
                }
            }
            if (BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED.equals(intent.getAction())) {
                try {
                    mActiveDeviceQueue.put(intent);
                } catch (InterruptedException e) {
                    Assert.fail("Cannot add Intent to the device queue: " + e.getMessage());
                }
            }
        }
    }

    private void verifyConnectionStateIntent(int timeoutMs, BluetoothDevice device,
                                             int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mConnectionStateChangedQueue);
        Assert.assertNotNull(intent);
        Assert.assertEquals(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                            intent.getAction());
        Assert.assertEquals(device, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(newState, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        Assert.assertEquals(prevState, intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                                                          -1));
    }

    private void verifyNoConnectionStateIntent(int timeoutMs) {
        Intent intent = TestUtils.waitForNoIntent(timeoutMs, mConnectionStateChangedQueue);
        Assert.assertNull(intent);
    }

    private void verifyAudioStateIntent(int timeoutMs, BluetoothDevice device,
                                             int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mAudioStateChangedQueue);
        Assert.assertNotNull(intent);
        Assert.assertEquals(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED, intent.getAction());
        Assert.assertEquals(device, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(newState, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        Assert.assertEquals(prevState, intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                                                          -1));
    }

    private void verifyNoAudioStateIntent(int timeoutMs) {
        Intent intent = TestUtils.waitForNoIntent(timeoutMs, mAudioStateChangedQueue);
        Assert.assertNull(intent);
    }

    private void verifyCodecConfigIntent(int timeoutMs, BluetoothDevice device,
                                         BluetoothCodecStatus codecStatus) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mCodecConfigChangedQueue);
        Assert.assertNotNull(intent);
        Assert.assertEquals(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED, intent.getAction());
        Assert.assertEquals(device, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(codecStatus,
                            intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS));
    }

    private void verifyNoCodecConfigIntent(int timeoutMs) {
        Intent intent = TestUtils.waitForNoIntent(timeoutMs, mCodecConfigChangedQueue);
        Assert.assertNull(intent);
    }

    private void veifyActiveDeviceIntent(int timeoutMs, BluetoothDevice device) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mActiveDeviceQueue);
        Assert.assertNotNull(intent);
        Assert.assertEquals(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED, intent.getAction());
        Assert.assertEquals(device, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
    }

    @Test
    public void testGetA2dpService() {
        Assert.assertEquals(mA2dpService, A2dpService.getA2dpService());
    }

    @Test
    public void testStopA2dpService() {
        // Prepare: connect and set active device
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        connectDevice(mTestDevice);
        Assert.assertTrue(mA2dpService.setActiveDevice(mTestDevice));
        verify(mMockNativeInterface).setActiveDevice(mTestDevice);

        Assert.assertTrue(mA2dpService.stop());

        // Verify that setActiveDevice(null) was called during shutdown
        verify(mMockNativeInterface).setActiveDevice(null);
        Assert.assertTrue(mA2dpService.start());
    }

    /**
     * Test get priority for BluetoothDevice
     */
    @Test
    public void testGetPriority() {
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        Assert.assertEquals("Initial device priority",
                            BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                            mA2dpService.getConnectionPolicy(mTestDevice));

        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        Assert.assertEquals("Setting device priority to PRIORITY_OFF",
                            BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                            mA2dpService.getConnectionPolicy(mTestDevice));

        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        Assert.assertEquals("Setting device priority to PRIORITY_ON",
                            BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                            mA2dpService.getConnectionPolicy(mTestDevice));
    }

    /**
     *  Test okToConnect method using various test cases
     */
    @Test
    public void testOkToConnect() {
        int badPriorityValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_NONE, badPriorityValue, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_BONDING, badPriorityValue, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, true);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_ALLOWED, true);
        testOkToConnectCase(mTestDevice,
                BluetoothDevice.BOND_BONDED, badPriorityValue, false);
        testOkToConnectCase(mTestDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mTestDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mTestDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mTestDevice,
                badBondState, badPriorityValue, false);
    }


    /**
     * Test that an outgoing connection to device that does not have A2DP Sink UUID is rejected
     */
    @Test
    public void testOutgoingConnectMissingAudioSinkUuid() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Return AudioSource UUID instead of AudioSink
        doReturn(new ParcelUuid[]{BluetoothUuid.A2DP_SOURCE}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mA2dpService.connect(mTestDevice));
    }

    /**
     * Test that an outgoing connection to device with PRIORITY_OFF is rejected
     */
    @Test
    public void testOutgoingConnectPriorityOff() {
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Set the device priority to PRIORITY_OFF so connect() should fail
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);

        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mA2dpService.connect(mTestDevice));
    }

    /**
     * Test that an outgoing connection times out
     */
    @Test
    public void testOutgoingConnectTimeout() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertTrue("Connect failed", mA2dpService.connect(mTestDevice));

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mTestDevice, BluetoothProfile.STATE_CONNECTING,
                                    BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                            mA2dpService.getConnectionState(mTestDevice));

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(A2dpStateMachine.sConnectTimeoutMs * 2,
                                    mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                                    BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
    }

    /**
     * Test that an outgoing connection/disconnection succeeds
     */
    @Test
    public void testOutgoingConnectDisconnectSuccess() {
        A2dpStackEvent connCompletedEvent;

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertTrue("Connect failed", mA2dpService.connect(mTestDevice));

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mTestDevice, BluetoothProfile.STATE_CONNECTING,
                                    BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                            mA2dpService.getConnectionState(mTestDevice));

        // Send a message to trigger connection completed
        connCompletedEvent = new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mTestDevice;
        connCompletedEvent.valueInt = A2dpStackEvent.CONNECTION_STATE_CONNECTED;
        mA2dpService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(TIMEOUT_MS, mTestDevice, BluetoothProfile.STATE_CONNECTED,
                                    BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));

        // Verify the list of connected devices
        Assert.assertTrue(mA2dpService.getConnectedDevices().contains(mTestDevice));

        // Send a disconnect request
        Assert.assertTrue("Disconnect failed", mA2dpService.disconnect(mTestDevice));

        // Verify the connection state broadcast, and that we are in Disconnecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mTestDevice, BluetoothProfile.STATE_DISCONNECTING,
                                    BluetoothProfile.STATE_CONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTING,
                            mA2dpService.getConnectionState(mTestDevice));

        // Send a message to trigger disconnection completed
        connCompletedEvent = new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mTestDevice;
        connCompletedEvent.valueInt = A2dpStackEvent.CONNECTION_STATE_DISCONNECTED;
        mA2dpService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(TIMEOUT_MS, mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                                    BluetoothProfile.STATE_DISCONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));

        // Verify the list of connected devices
        Assert.assertFalse(mA2dpService.getConnectedDevices().contains(mTestDevice));
    }

    /**
     * Test that an outgoing connection/disconnection succeeds
     */
    @Test
    public void testMaxConnectDevices() {
        A2dpStackEvent connCompletedEvent;
        BluetoothDevice[] testDevices = new BluetoothDevice[MAX_CONNECTED_AUDIO_DEVICES];
        BluetoothDevice extraTestDevice;

        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Prepare and connect all test devices
        for (int i = 0; i < MAX_CONNECTED_AUDIO_DEVICES; i++) {
            BluetoothDevice testDevice = TestUtils.getTestDevice(mAdapter, i);
            testDevices[i] = testDevice;
            when(mDatabaseManager.getProfileConnectionPolicy(testDevice, BluetoothProfile.A2DP))
                    .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            // Send a connect request
            Assert.assertTrue("Connect failed", mA2dpService.connect(testDevice));
            // Verify the connection state broadcast, and that we are in Connecting state
            verifyConnectionStateIntent(TIMEOUT_MS, testDevice, BluetoothProfile.STATE_CONNECTING,
                                        BluetoothProfile.STATE_DISCONNECTED);
            Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                                mA2dpService.getConnectionState(testDevice));
            // Send a message to trigger connection completed
            connCompletedEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
            connCompletedEvent.device = testDevice;
            connCompletedEvent.valueInt = A2dpStackEvent.CONNECTION_STATE_CONNECTED;
            mA2dpService.messageFromNative(connCompletedEvent);

            // Verify the connection state broadcast, and that we are in Connected state
            verifyConnectionStateIntent(TIMEOUT_MS, testDevice, BluetoothProfile.STATE_CONNECTED,
                                        BluetoothProfile.STATE_CONNECTING);
            Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                                mA2dpService.getConnectionState(testDevice));
            // Verify the list of connected devices
            Assert.assertTrue(mA2dpService.getConnectedDevices().contains(testDevice));
        }

        // Prepare and connect the extra test device. The connect request should fail
        extraTestDevice = TestUtils.getTestDevice(mAdapter, MAX_CONNECTED_AUDIO_DEVICES);
        when(mDatabaseManager.getProfileConnectionPolicy(extraTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mA2dpService.connect(extraTestDevice));
    }

    /**
     * Test that only CONNECTION_STATE_CONNECTED or CONNECTION_STATE_CONNECTING A2DP stack events
     * will create a state machine.
     */
    @Test
    public void testCreateStateMachineStackEvents() {
        A2dpStackEvent stackEvent;

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_CONNECTING,
                                            BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                                            BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_NONE);
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_CONNECTED - state machine should be created
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_CONNECTED,
                                            BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                                            BluetoothProfile.STATE_CONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_NONE);
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTING - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mTestDevice,
                                                      BluetoothProfile.STATE_DISCONNECTING,
                                                      BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mTestDevice,
                                                      BluetoothProfile.STATE_DISCONNECTED,
                                                      BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));
    }

    /**
     * Test that EVENT_TYPE_AUDIO_STATE_CHANGED and EVENT_TYPE_CODEC_CONFIG_CHANGED events
     * are processed.
     */
    @Test
    public void testProcessAudioStateChangedCodecConfigChangedEvents() {
        A2dpStackEvent stackEvent;
        BluetoothCodecConfig codecConfigSbc =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        0, 0, 0, 0);       // Codec-specific fields
        BluetoothCodecConfig codecConfig = codecConfigSbc;
        BluetoothCodecConfig[] codecsLocalCapabilities = new BluetoothCodecConfig[1];
        BluetoothCodecConfig[] codecsSelectableCapabilities = new BluetoothCodecConfig[1];
        codecsLocalCapabilities[0] = codecConfigSbc;
        codecsSelectableCapabilities[0] = codecConfigSbc;
        BluetoothCodecStatus codecStatus = new BluetoothCodecStatus(codecConfig,
                                                    Arrays.asList(codecsLocalCapabilities),
                                                    Arrays.asList(codecsSelectableCapabilities));

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: EVENT_TYPE_AUDIO_STATE_CHANGED - state machine should not be created
        generateUnexpectedAudioMessageFromNative(mTestDevice, A2dpStackEvent.AUDIO_STATE_STARTED,
                                                 BluetoothA2dp.STATE_PLAYING,
                                                 BluetoothA2dp.STATE_NOT_PLAYING);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: EVENT_TYPE_CODEC_CONFIG_CHANGED - state machine should not be created
        generateUnexpectedCodecMessageFromNative(mTestDevice, codecStatus);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_CONNECTED - state machine should be created
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_CONNECTED,
                                            BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: EVENT_TYPE_AUDIO_STATE_CHANGED - Intent broadcast should be generated
        // NOTE: The first message (STATE_PLAYING -> STATE_NOT_PLAYING) is generated internally
        // by the state machine when Connected, and needs to be extracted first before generating
        // the actual message from native.
        verifyAudioStateIntent(TIMEOUT_MS, mTestDevice, BluetoothA2dp.STATE_NOT_PLAYING,
                               BluetoothA2dp.STATE_PLAYING);
        generateAudioMessageFromNative(mTestDevice,
                                       A2dpStackEvent.AUDIO_STATE_STARTED,
                                       BluetoothA2dp.STATE_PLAYING,
                                       BluetoothA2dp.STATE_NOT_PLAYING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: EVENT_TYPE_CODEC_CONFIG_CHANGED - Intent broadcast should be generated
        generateCodecMessageFromNative(mTestDevice, codecStatus);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                                            BluetoothProfile.STATE_CONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_NONE);
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));
    }

    /**
     * Test that a state machine in DISCONNECTED state is removed only after the device is unbond.
     */
    @Test
    public void testDeleteStateMachineUnbondEvents() {
        A2dpStackEvent stackEvent;

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_CONNECTING,
                                            BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_NONE);
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_CONNECTED - state machine is not removed
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_CONNECTED,
                                            BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_NONE);
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTING - state machine is not removed
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTING,
                                            BluetoothProfile.STATE_CONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTING,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_NONE);
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                                            BluetoothProfile.STATE_DISCONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));
        // Device unbond - state machine is removed
        mA2dpService.bondStateChanged(mTestDevice, BluetoothDevice.BOND_NONE);
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));
    }

    /**
     * Test that a CONNECTION_STATE_DISCONNECTED A2DP stack event will remove the state machine
     * only if the device is unbond.
     */
    @Test
    public void testDeleteStateMachineDisconnectEvents() {
        A2dpStackEvent stackEvent;

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mTestDevice, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_CONNECTING,
                                            BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                                            BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine remains
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_CONNECTING,
                                            BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // Device bond state marked as unbond - state machine is not removed
        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        Assert.assertTrue(mA2dpService.getDevices().contains(mTestDevice));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine is removed
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                                            BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                            mA2dpService.getConnectionState(mTestDevice));
        Assert.assertFalse(mA2dpService.getDevices().contains(mTestDevice));
    }

    /**
     * Test that whether active device been removed after enable silence mode
     */
    @Test
    public void testSetSilenceMode() {
        BluetoothDevice otherDevice = mAdapter.getRemoteDevice("05:04:03:02:01:00");
        connectDevice(mTestDevice);
        connectDevice(otherDevice);
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        doReturn(true)
                .when(mMockNativeInterface)
                .setSilenceDevice(any(BluetoothDevice.class), anyBoolean());

        // Test whether active device been removed after enable silence mode.
        Assert.assertTrue(mA2dpService.setActiveDevice(mTestDevice));
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());
        Assert.assertTrue(mA2dpService.setSilenceMode(mTestDevice, true));
        verify(mMockNativeInterface).setSilenceDevice(mTestDevice, true);
        Assert.assertNull(mA2dpService.getActiveDevice());

        // Test whether active device been resumeed after disable silence mode.
        Assert.assertTrue(mA2dpService.setSilenceMode(mTestDevice, false));
        verify(mMockNativeInterface).setSilenceDevice(mTestDevice, false);
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());

        // Test that active device should not be changed when silence a non-active device
        Assert.assertTrue(mA2dpService.setActiveDevice(mTestDevice));
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());
        Assert.assertTrue(mA2dpService.setSilenceMode(otherDevice, true));
        verify(mMockNativeInterface).setSilenceDevice(otherDevice, true);
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());

        // Test that active device should not be changed when another device exits silence mode
        Assert.assertTrue(mA2dpService.setSilenceMode(otherDevice, false));
        verify(mMockNativeInterface).setSilenceDevice(otherDevice, false);
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());
    }

    @Test
    public void testSetActiveDevice_withNull_returnsFalse() {
        // Null is not accepted.
        Assert.assertFalse(mA2dpService.setActiveDevice(null));
    }

    /**
     * Test whether removeActiveDevice(false) suppresses noisy intent.
     * Music should keep playing.
     * (e.g. user selected LE headset via UI)
     */
    @Test
    public void testRemoveActiveDevice_whenStopAudioIsFalse_suppressNoisyIntent() {
        connectDevice(mTestDevice);
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        Assert.assertTrue(mA2dpService.setActiveDevice(mTestDevice));
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());
        AudioManager audioManager = mock(AudioManager.class);
        mA2dpService.mAudioManager = audioManager;

        Assert.assertTrue(mA2dpService.disconnect(mTestDevice));
        verifyConnectionStateIntent(TIMEOUT_MS, mTestDevice, BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        mA2dpService.removeActiveDevice(false);

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);
        verify(audioManager).handleBluetoothActiveDeviceChanged(
                isNull(), eq(mTestDevice), connectionInfoArgumentCaptor.capture());
        BluetoothProfileConnectionInfo connectionInfo =
                connectionInfoArgumentCaptor.getValue();
        // Should suppress noisy intent. (i.e. Music should keep playing)
        Assert.assertTrue(connectionInfo.isSuppressNoisyIntent());
    }

    /**
     * Test whether removeActiveDevice(true) does not suppress noisy intent.
     * Music should pause.
     * (e.g. The solely connected BT device is disconnected)
     */
    @Test
    public void testRemoveActiveDevice_whenStopAudioIsFalse_doesNotSuppressNoisyIntent() {
        connectDevice(mTestDevice);
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        Assert.assertTrue(mA2dpService.setActiveDevice(mTestDevice));
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());
        AudioManager audioManager = mock(AudioManager.class);
        mA2dpService.mAudioManager = audioManager;

        Assert.assertTrue(mA2dpService.disconnect(mTestDevice));
        verifyConnectionStateIntent(TIMEOUT_MS, mTestDevice, BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        mA2dpService.removeActiveDevice(true);

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);
        verify(audioManager).handleBluetoothActiveDeviceChanged(
                isNull(), eq(mTestDevice), connectionInfoArgumentCaptor.capture());
        BluetoothProfileConnectionInfo connectionInfo =
                connectionInfoArgumentCaptor.getValue();
        // Should not suppress noisy intent. (i.e. Music should pause)
        Assert.assertFalse(connectionInfo.isSuppressNoisyIntent());
    }

    /**
     * Test that whether updateOptionalCodecsSupport() method is working as intended
     * when a Bluetooth device is connected with A2DP.
     */
    @Test
    public void testUpdateOptionalCodecsSupport() {
        int verifySupportTime = 0;
        int verifyNotSupportTime = 0;
        int verifyEnabledTime = 0;
        // Test for device supports optional codec
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                ++verifySupportTime, verifyNotSupportTime, ++verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                ++verifySupportTime, verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                ++verifySupportTime, verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                verifySupportTime, verifyNotSupportTime, ++verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                verifySupportTime, verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                verifySupportTime, verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                ++verifySupportTime, verifyNotSupportTime, ++verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                ++verifySupportTime, verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED, true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                ++verifySupportTime, verifyNotSupportTime, verifyEnabledTime);

        // Test for device not supports optional codec
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                verifySupportTime, ++verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                verifySupportTime, ++verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                verifySupportTime, ++verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                verifySupportTime, ++verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                verifySupportTime, ++verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                verifySupportTime, ++verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                verifySupportTime, verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                verifySupportTime, verifyNotSupportTime, verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED, false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                verifySupportTime, verifyNotSupportTime, verifyEnabledTime);
    }

    /**
     * Tests that {@link A2dpService#sendPreferredAudioProfileChangeToAudioFramework()} sends
     * requests to the audio framework when there is an active A2DP device.
     */
    @Test
    public void testSendPreferredAudioProfileChangeToAudioFramework() {
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        Assert.assertTrue(mA2dpService.removeActiveDevice(true));
        Assert.assertNull(mA2dpService.getActiveDevice());

        // Send 0 requests when the active device is null
        Assert.assertEquals(0, mA2dpService.sendPreferredAudioProfileChangeToAudioFramework());

        // Send 1 request when there is an A2DP active device
        connectDevice(mTestDevice);
        Assert.assertTrue(mA2dpService.setActiveDevice(mTestDevice));
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());
        Assert.assertEquals(1, mA2dpService.sendPreferredAudioProfileChangeToAudioFramework());
    }

    @Test
    public void testDumpDoesNotCrash() {
        mA2dpService.dump(new StringBuilder());
    }

    private void connectDevice(BluetoothDevice device) {
        connectDeviceWithCodecStatus(device, null);
    }

    @Test
    public void testActiveDevice() {
        connectDevice(mTestDevice);

        /* Trigger setting active device */
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        Assert.assertTrue(mA2dpService.setActiveDevice(mTestDevice));

        /* Check if setting active devices sets right device */
        Assert.assertEquals(mTestDevice, mA2dpService.getActiveDevice());

        /* Since A2dpService called AudioManager - assume Audio manager calles properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        mA2dpService.updateAndBroadcastActiveDevice(mTestDevice);

        veifyActiveDeviceIntent(TIMEOUT_MS, mTestDevice);
    }

    private void connectDeviceWithCodecStatus(BluetoothDevice device,
            BluetoothCodecStatus codecStatus) {
        A2dpStackEvent connCompletedEvent;

        List<BluetoothDevice> prevConnectedDevices = mA2dpService.getConnectedDevices();

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(device);
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(device);
        doReturn(true)
                .when(mMockNativeInterface)
                .setCodecConfigPreference(
                        any(BluetoothDevice.class), any(BluetoothCodecConfig[].class));

        // Send a connect request
        Assert.assertTrue("Connect failed", mA2dpService.connect(device));

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, device, BluetoothProfile.STATE_CONNECTING,
                                    BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                            mA2dpService.getConnectionState(device));

        if (codecStatus != null) {
            generateCodecMessageFromNative(device, codecStatus);
        }

        // Send a message to trigger connection completed
        connCompletedEvent = new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt = A2dpStackEvent.CONNECTION_STATE_CONNECTED;
        mA2dpService.messageFromNative(connCompletedEvent);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(TIMEOUT_MS, device, BluetoothProfile.STATE_CONNECTED,
                                    BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED,
                            mA2dpService.getConnectionState(device));

        // Verify that the device is in the list of connected devices
        Assert.assertTrue(mA2dpService.getConnectedDevices().contains(device));
        // Verify the list of previously connected devices
        for (BluetoothDevice prevDevice : prevConnectedDevices) {
            Assert.assertTrue(mA2dpService.getConnectedDevices().contains(prevDevice));
        }
    }

    private void generateConnectionMessageFromNative(BluetoothDevice device, int newConnectionState,
                                                     int oldConnectionState) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt = newConnectionState;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        verifyConnectionStateIntent(TIMEOUT_MS, device, newConnectionState, oldConnectionState);
    }

    private void generateUnexpectedConnectionMessageFromNative(BluetoothDevice device,
                                                               int newConnectionState,
                                                               int oldConnectionState) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt = newConnectionState;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        verifyNoConnectionStateIntent(TIMEOUT_MS);
    }

    private void generateAudioMessageFromNative(BluetoothDevice device, int audioStackEvent,
                                                int newAudioState, int oldAudioState) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt = audioStackEvent;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the audio state broadcast
        verifyAudioStateIntent(TIMEOUT_MS, device, newAudioState, oldAudioState);
    }

    private void generateUnexpectedAudioMessageFromNative(BluetoothDevice device,
                                                          int audioStackEvent, int newAudioState,
                                                          int oldAudioState) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt = audioStackEvent;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the audio state broadcast
        verifyNoAudioStateIntent(TIMEOUT_MS);
    }

    private void generateCodecMessageFromNative(BluetoothDevice device,
                                                BluetoothCodecStatus codecStatus) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED);
        stackEvent.device = device;
        stackEvent.codecStatus = codecStatus;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the codec status broadcast
        verifyCodecConfigIntent(TIMEOUT_MS, device, codecStatus);
    }

    private void generateUnexpectedCodecMessageFromNative(BluetoothDevice device,
                                                          BluetoothCodecStatus codecStatus) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED);
        stackEvent.device = device;
        stackEvent.codecStatus = codecStatus;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the codec status broadcast
        verifyNoCodecConfigIntent(TIMEOUT_MS);
    }

    /**
     * Helper function to test okToConnect() method.
     *
     * @param device test device
     * @param bondState bond state value, could be invalid
     * @param priority value, could be invalid, coudl be invalid
     * @param expected expected result from okToConnect()
     */
    private void testOkToConnectCase(BluetoothDevice device, int bondState, int priority,
            boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP))
                .thenReturn(priority);

        // Test when the AdapterService is in non-quiet mode: the result should not depend
        // on whether the connection request is outgoing or incoming.
        doReturn(false).when(mAdapterService).isQuietModeEnabled();
        Assert.assertEquals(expected, mA2dpService.okToConnect(device, true));  // Outgoing
        Assert.assertEquals(expected, mA2dpService.okToConnect(device, false)); // Incoming

        // Test when the AdapterService is in quiet mode: the result should always be
        // false when the connection request is incoming.
        doReturn(true).when(mAdapterService).isQuietModeEnabled();
        Assert.assertEquals(expected, mA2dpService.okToConnect(device, true));  // Outgoing
        Assert.assertEquals(false, mA2dpService.okToConnect(device, false)); // Incoming
    }

    /**
     * Helper function to test updateOptionalCodecsSupport() method
     *
     * @param previousSupport previous optional codec support status
     * @param support new optional codec support status
     * @param previousEnabled previous optional codec enable status
     * @param verifySupportTime verify times of optional codec set to support
     * @param verifyNotSupportTime verify times of optional codec set to not support
     * @param verifyEnabledTime verify times of optional codec set to enabled
     */
    private void testUpdateOptionalCodecsSupportCase(int previousSupport, boolean support,
            int previousEnabled, int verifySupportTime, int verifyNotSupportTime,
            int verifyEnabledTime) {
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));

        BluetoothCodecConfig codecConfigSbc =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        0, 0, 0, 0);       // Codec-specific fields
        BluetoothCodecConfig codecConfigAac =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        0, 0, 0, 0);       // Codec-specific fields

        BluetoothCodecConfig[] codecsLocalCapabilities;
        BluetoothCodecConfig[] codecsSelectableCapabilities;
        if (support) {
            codecsLocalCapabilities = new BluetoothCodecConfig[2];
            codecsSelectableCapabilities = new BluetoothCodecConfig[2];
            codecsLocalCapabilities[0] = codecConfigSbc;
            codecsLocalCapabilities[1] = codecConfigAac;
            codecsSelectableCapabilities[0] = codecConfigSbc;
            codecsSelectableCapabilities[1] = codecConfigAac;
        } else {
            codecsLocalCapabilities = new BluetoothCodecConfig[1];
            codecsSelectableCapabilities = new BluetoothCodecConfig[1];
            codecsLocalCapabilities[0] = codecConfigSbc;
            codecsSelectableCapabilities[0] = codecConfigSbc;
        }
        BluetoothCodecConfig[] badCodecsSelectableCapabilities;
        badCodecsSelectableCapabilities = new BluetoothCodecConfig[1];
        badCodecsSelectableCapabilities[0] = codecConfigAac;

        BluetoothCodecStatus codecStatus = new BluetoothCodecStatus(codecConfigSbc,
                Arrays.asList(codecsLocalCapabilities),
                Arrays.asList(codecsSelectableCapabilities));
        BluetoothCodecStatus badCodecStatus = new BluetoothCodecStatus(codecConfigAac,
                Arrays.asList(codecsLocalCapabilities),
                Arrays.asList(badCodecsSelectableCapabilities));

        when(mDatabaseManager.getA2dpSupportsOptionalCodecs(mTestDevice))
                .thenReturn(previousSupport);
        when(mDatabaseManager.getA2dpOptionalCodecsEnabled(mTestDevice))
                .thenReturn(previousEnabled);

        // Generate connection request from native with bad codec status
        connectDeviceWithCodecStatus(mTestDevice, badCodecStatus);
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED);

        // Generate connection request from native with good codec status
        connectDeviceWithCodecStatus(mTestDevice, codecStatus);
        generateConnectionMessageFromNative(mTestDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED);

        // Check optional codec status is set properly
        verify(mDatabaseManager, times(verifyNotSupportTime)).setA2dpSupportsOptionalCodecs(
                mTestDevice, BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED);
        verify(mDatabaseManager, times(verifySupportTime)).setA2dpSupportsOptionalCodecs(
                mTestDevice, BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
        verify(mDatabaseManager, times(verifyEnabledTime)).setA2dpOptionalCodecsEnabled(
                mTestDevice, BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED);
    }

    private BluetoothCodecConfig buildBluetoothCodecConfig(int sourceCodecType,
            int codecPriority, int sampleRate, int bitsPerSample, int channelMode,
            long codecSpecific1, long codecSpecific2, long codecSpecific3, long codecSpecific4) {
        return new BluetoothCodecConfig.Builder()
                    .setCodecType(sourceCodecType)
                    .setCodecPriority(codecPriority)
                    .setSampleRate(sampleRate)
                    .setBitsPerSample(bitsPerSample)
                    .setChannelMode(channelMode)
                    .setCodecSpecific1(codecSpecific1)
                    .setCodecSpecific2(codecSpecific2)
                    .setCodecSpecific3(codecSpecific3)
                    .setCodecSpecific4(codecSpecific4)
                    .build();
    }
}
