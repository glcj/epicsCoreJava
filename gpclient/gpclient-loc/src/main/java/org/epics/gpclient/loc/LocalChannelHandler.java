/**
 * Copyright information and license terms for this software can be
 * found in the file LICENSE.TXT included with the distribution.
 */
package org.epics.gpclient.loc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.epics.gpclient.ReadCollector;
import org.epics.gpclient.WriteCollector;
import org.epics.gpclient.datasource.MultiplexedChannelHandler;
import org.epics.vtype.Alarm;
import org.epics.vtype.EnumDisplay;
import org.epics.vtype.Time;
import org.epics.vtype.VDouble;
import org.epics.vtype.VDoubleArray;
import org.epics.vtype.VEnum;
import org.epics.vtype.VString;
import org.epics.vtype.VType;

/**
 * Implementation for channels of a {@link LocalDataSource}.
 *
 * @author carcassi
 */
class LocalChannelHandler extends MultiplexedChannelHandler<Object, Object> {
    
    private static Logger log = Logger.getLogger(LocalChannelHandler.class.getName());

    LocalChannelHandler(String channelName) {
        super(channelName);
    }

    @Override
    public void connect() {
        processConnection(new Object());
    }

    @Override
    public void disconnect() {
        initialArguments = null;
        type = null;
        processConnection(null);
    }

    @Override
    protected synchronized void addReader(ReadCollector subscription) {
        // Override for test visibility purposes
        super.addReader(subscription);
    }

    @Override
    protected synchronized void addWriter(WriteCollector subscription) {
        // Override for test visibility purposes
        super.addWriter(subscription);
    }

    @Override
    protected synchronized void removeReader(ReadCollector subscription) {
        // Override for test visibility purposes
        super.removeReader(subscription);
    }

    @Override
    protected synchronized void removeWriter(WriteCollector subscription) {
        // Override for test visibility purposes
        super.removeWriter(subscription);
    }
    
    private Object checkValue(Object value) {
        if (type != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("Value " + value + " is not of type " + type.getSimpleName());
        }
        return value;
    }

    @Override
    public void write(Object newValue) {
        // XXX Actual write is not enforcing the type!

        if (VEnum.class.equals(type)) {
            // Handle enum writes
            int newIndex = -1;
            // TODO calculate the newIndex from the new value
            // Add error message if type does not match
            VEnum firstEnum = (VEnum) initialValue;
            newValue = VEnum.of(newIndex, firstEnum.getDisplay(), Alarm.none(), Time.now());
        } else {

            // If the string can be parse to a number, do it
            if (newValue instanceof String) {
                String value = (String) newValue;
                try {
                    newValue = Double.valueOf(value);
                } catch (NumberFormatException ex) {
                }
            }
            // If new value is not a VType, try to convert it
            if (newValue != null && !(newValue instanceof VType)) {
                newValue = checkValue(VType.toVTypeChecked(newValue));
            }
        }
        processMessage(newValue);
    }

    @Override
    protected boolean isWriteConnected(Object payload) {
        return isConnected(payload);
    }
    
    private Object initialArguments;
    private Object initialValue;
    private Class<?> type;
    
    synchronized void setInitialValue(Object value) {
        if (initialArguments != null && !initialArguments.equals(value)) {
            String message = "Different initialization for local channel " + getChannelName() + ": " + value + " but was " + initialArguments;
            log.log(Level.WARNING, message);
            throw new RuntimeException(message);
        }
        initialArguments = value;
        if (getLastMessagePayload() == null) {
            if (VEnum.class.equals(type)) {
                List<?> args = (List<?>) initialArguments;
                // TODO error message if not Number
                int index = ((Number) args.get(0)).intValue();
                List<String> labels = new ArrayList<>();
                for (Object arg : args.subList(1, args.size())) {
                    // TODO error message if not String
                    labels.add((String) arg);
                }
                
                initialValue = VEnum.of(index, EnumDisplay.of(labels), Alarm.none(), Time.now());
            } else {
                initialValue = checkValue(VType.toVTypeChecked(value));
            }
            processMessage(initialValue);
        }
    }
    
    synchronized void setType(String typeName) {
        if (typeName == null) {
            return;
        }
        Class<?> newType = null;
        if ("VDouble".equals(typeName)) {
            newType = VDouble.class;
        }
        if ("VString".equals(typeName)) {
            newType = VString.class;
        }
        if ("VDoubleArray".equals(typeName)) {
            newType = VDoubleArray.class;
        }
//        if ("VStringArray".equals(typeName)) {
//            newType = VStringArray.class;
//        }
//        if ("VTable".equals(typeName)) {
//            newType = VTable.class;
//        }
        if ("VEnum".equals(typeName)) {
            newType = VEnum.class;
        }
        if (newType == null) {
            throw new IllegalArgumentException("Type " + typeName + " for channel " + getChannelName() + " is not supported by local datasource.");
        }
        if (type != null && !type.equals(newType)) {
            throw new IllegalArgumentException("Type mismatch for channel " + getChannelName() + ": " + typeName + " but was " + type.getSimpleName());
        }
        type = newType;
    }

    @Override
    public synchronized Map<String, Object> getProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("Name", getChannelName());
        properties.put("Type", type);
        properties.put("Initial Value", initialArguments);
        return properties;
    }
    
}
