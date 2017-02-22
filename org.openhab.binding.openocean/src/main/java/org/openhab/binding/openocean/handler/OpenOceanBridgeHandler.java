/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.openocean.handler;

import static org.openhab.binding.openocean.OpenOceanBindingConstants.*;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.core.status.ConfigStatusMessage;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.ConfigStatusBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.openocean.internal.OpenOceanConfigStatusMessage;
import org.openhab.binding.openocean.internal.OpenOceanException;
import org.openhab.binding.openocean.messages.Response;
import org.openhab.binding.openocean.messages.Response.ResponseType;
import org.openhab.binding.openocean.transceiver.ESP3Packet;
import org.openhab.binding.openocean.transceiver.ESP3PacketFactory;
import org.openhab.binding.openocean.transceiver.ESP3PacketListener;
import org.openhab.binding.openocean.transceiver.Helper;
import org.openhab.binding.openocean.transceiver.OpenOceanSerialTransceiver;
import org.openhab.binding.openocean.transceiver.OpenOceanTransceiver;
import org.openhab.binding.openocean.transceiver.ResponseListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link OpenOceanBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Daniel Weber - Initial contribution
 */
public class OpenOceanBridgeHandler extends ConfigStatusBridgeHandler implements ESP3PacketListener {

    private Logger logger = LoggerFactory.getLogger(OpenOceanBridgeHandler.class);

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE);

    private OpenOceanTransceiver transceiver;
    private ScheduledFuture<?> connectorTask;

    private int[] baseId = null;

    public OpenOceanBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (transceiver == null) {
            updateStatus(ThingStatus.OFFLINE);
            return;
        }

        if (channelUID.getId().equals(REPEATERMODE)) {
            if (command instanceof RefreshType) {
                transceiver.sendESP3Packet(ESP3PacketFactory.CO_RD_REPEATER, new ResponseListener() {
                    @Override
                    public void responseReceived(Response response) {
                        if (response.isOK()) {
                            int data[] = response.getData();
                            switch (data[2]) {
                                case 0:
                                    updateState(channelUID, StringType.valueOf(REPEATERMODE_OFF));
                                    break;
                                case 1:
                                    updateState(channelUID, StringType.valueOf(REPEATERMODE_LEVEL_1));
                                    break;
                                case 2:
                                    updateState(channelUID, StringType.valueOf(REPEATERMODE_LEVEL_2));
                                    break;
                                default:
                                    break;
                            }
                        } else {
                            updateState(channelUID, StringType.valueOf(REPEATERMODE_OFF));
                        }
                    }
                });
            } else if (command instanceof StringType) {
                transceiver.sendESP3Packet(ESP3PacketFactory.CO_WR_REPEATER((StringType) command),
                        new ResponseListener() {

                            @Override
                            public void responseReceived(Response response) {
                                if (response.isOK()) {
                                    updateState(channelUID, (StringType) command);
                                }
                            }
                        });
            }
        }
    }

    @Override
    public void initialize() {

        // TODO use scheduled task to try reconnect to gateway
        updateStatus(ThingStatus.OFFLINE);
        if (connectorTask == null || connectorTask.isCancelled()) {
            connectorTask = scheduler.schedule(new Runnable() {

                @Override
                public void run() {
                    initTransceiver();
                }

            }, 1, TimeUnit.SECONDS);
        }
    }

    private void initTransceiver() {

        transceiver = new OpenOceanSerialTransceiver((String) getThing().getConfiguration().get(PORT));
        transceiver.addPacketListener(this);

        try {
            transceiver.Initialize();
        } catch (OpenOceanException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            return;
        }

        transceiver.StartReading(scheduler);
        updateStatus(ThingStatus.ONLINE);

        logger.debug("request base id");
        transceiver.sendESP3Packet(ESP3PacketFactory.CO_RD_IDBASE, new ResponseListener() {

            @Override
            public void responseReceived(Response response) {

                if (response.isOK()) {
                    baseId = response.getData(1, 4);
                    updateProperty(PROPERTY_Base_ID, Helper.bytesToHexString(baseId));
                    updateProperty(PROPERTY_REMAINING_WRITE_CYCLES_Base_ID,
                            Integer.toString(response.getOptionalData()[0]));

                    logger.debug("BaseId of transceiver: " + Helper.bytesToHexString(baseId));
                    logger.debug("Remaining write cycles: " + response.getOptionalData()[0]);
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Could not get BaseId");
                }
            }
        });

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        transceiver.sendESP3Packet(ESP3PacketFactory.CO_RD_VERSION, new ResponseListener() {

            @Override
            public void responseReceived(Response response) {

                if (response.getResponseType() == ResponseType.RET_OK) {

                    int[] data = response.getData();
                    String version = String.format("%d.%d.%d.%d", data[1] & 0xff, data[2] & 0xff, data[3] & 0xff,
                            data[4] & 0xff);
                    updateProperty(PROPERTY_APP_VERSION, version);

                    version = String.format("%d.%d.%d.%d", data[5] & 0xff, data[6] & 0xff, data[7] & 0xff,
                            data[8] & 0xff);
                    updateProperty(PROPERTY_API_VERSION, version);

                    int[] chip = new int[4];
                    System.arraycopy(data, 9, chip, 0, 4);
                    updateProperty(PROPERTY_CHIP_ID, Helper.bytesToHexString(chip));

                    StringBuffer description = new StringBuffer();
                    for (int i = 17; i < data.length; i++) {
                        description.append((char) (data[i] & 0xff));
                    }
                    updateProperty(PROPERTY_DESCRIPTION, description.toString());

                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Could not get BaseId");
                }
            }
        });
    }

    @Override
    public void dispose() {
        if (transceiver != null) {
            transceiver.ShutDown();
        }
        super.dispose();
    }

    @Override
    public void handleRemoval() {
        if (transceiver != null) {
            transceiver.ShutDown();
        }
        updateStatus(ThingStatus.REMOVED);
    }

    @Override
    public Collection<ConfigStatusMessage> getConfigStatus() {

        // The serial port must be provided
        final String bridgePort = (String) getThing().getConfiguration().get(PORT);
        Collection<ConfigStatusMessage> configStatusMessages;

        // Check whether an IP address is provided
        if (bridgePort == null || bridgePort.isEmpty()) {
            configStatusMessages = Collections.singletonList(ConfigStatusMessage.Builder.error(PORT)
                    .withMessageKeySuffix(OpenOceanConfigStatusMessage.PORT_MISSING.getMessageKey()).withArguments(PORT)
                    .build());
        } else {
            configStatusMessages = Collections.emptyList();
        }

        return configStatusMessages;
    }

    @Override
    public void espPacketReceived(ESP3Packet packet) {

    }
}
