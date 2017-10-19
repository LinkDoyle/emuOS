/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package emuos.os;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * @author Link
 */
public class DeviceManager {
    // time in milliseconds between successive task executions.
    private static final int PERIOD = 1000;
    private static final int REST_TIME_INTERVAL = 1;
    private final Timer timer = new Timer("Timer-DeviceManager", true);
    // DeviceID --> DeviceList --> Device Map
    private final Map<Integer, DeviceList> deviceListMap = new HashMap<>();
    private final BlockingQueue<ProcessControlBlock> finishedQueue = new LinkedBlockingDeque<>();
    private final Collection<Handler> finishedHandlers = new LinkedList<>();
    private boolean running;

    public DeviceManager() {
        DeviceList deviceListA = new DeviceList('A', 2);
        DeviceList deviceListB = new DeviceList('B', 3);
        DeviceList deviceListC = new DeviceList('C', 3);
        deviceListMap.put((int) 'A', deviceListA);
        deviceListMap.put((int) 'B', deviceListB);
        deviceListMap.put((int) 'C', deviceListC);
    }

    public BlockingQueue<ProcessControlBlock> getFinishedQueue() {
        return finishedQueue;
    }


    public boolean addFinishedHandler(Handler handler) {
        synchronized (finishedHandlers) {
            return finishedHandlers.add(handler);
        }
    }

    public boolean removeFinishedHandler(Handler handler) {
        synchronized (finishedHandlers) {
            return finishedHandlers.remove(handler);
        }
    }

    public synchronized void start() {
        if (!running) {
            timer.scheduleAtFixedRate(new DeviceTimerTask(), 0, PERIOD);
            running = true;
        }
    }

    public synchronized void stop() {
        if (running) {
            timer.cancel();
            timer.purge();
            running = false;
        }
    }

    public synchronized void alloc(RequestInfo requestInfo) {
        assert requestInfo.pcb.getState() == ProcessControlBlock.ProcessState.BLOCKED;
        DeviceList deviceList = deviceListMap.get(requestInfo.getDeviceType());
        if (deviceList != null) {
            deviceList.getWaitingQueue().add(requestInfo);
        }
    }

    public synchronized boolean detach(ProcessControlBlock pcb) {
        assert pcb.getState() == ProcessControlBlock.ProcessState.BLOCKED;
        boolean found = false;
        for (DeviceList list : deviceListMap.values()) {
            List<DeviceInfo> deviceInfoList = list.getDeviceInfoList();
            for (int i = 0, deviceInfoListSize = deviceInfoList.size(); i < deviceInfoListSize; i++) {
                DeviceInfo info = deviceInfoList.get(i);
                if (info.PCB.equals(pcb)) {
                    found = true;
                    deviceInfoList.remove(i);
                }
            }
        }
        return found;
    }

    public synchronized List<Snapshot> snap() {
        List<Snapshot> snapshots = new LinkedList<>();
        for (DeviceList list : deviceListMap.values()) {
            for (DeviceInfo info : list.getDeviceInfoList()) {
                Snapshot.Status status = Snapshot.Status.IDLE;
                int PID = 0;
                if (!info.isIdle()) {
                    status = Snapshot.Status.BUSY;
                    PID = info.getPCB().getPID();
                }
                snapshots.add(new Snapshot(info.getType(), status, PID));
            }
        }
        return snapshots;
    }

    public interface Handler {
        void handler(DeviceInfo deviceInfo);
    }

    public static class DeviceInfo {
        private final int type;
        // rest time in milliseconds
        private int restTime;
        private ProcessControlBlock PCB;

        DeviceInfo(int type) {
            this.type = type;
            restTime = 0;
            PCB = null;
        }

        boolean isIdle() {
            return PCB == null;
        }

        void release() {
            PCB = null;
            restTime = 0;
        }

        void alloc(ProcessControlBlock user, int time) {
            this.PCB = user;
            this.restTime = time;
        }

        public ProcessControlBlock getPCB() {
            return PCB;
        }

        public int getType() {
            return type;
        }
    }

    public static class RequestInfo {
        private final ProcessControlBlock pcb;
        private final int deviceType;
        private final int time;

        public RequestInfo(ProcessControlBlock pcb, int deviceType, int time) {
            this.pcb = pcb;
            this.deviceType = deviceType;
            this.time = time;
        }

        public ProcessControlBlock getPCB() {
            return pcb;
        }

        public int getDeviceType() {
            return deviceType;
        }

        public int getTime() {
            return time;
        }
    }

    private static class DeviceList {
        final Queue<RequestInfo> waitingQueue;
        final List<DeviceInfo> deviceInfoList;

        DeviceList(int kind, int count) {
            deviceInfoList = new ArrayList<>();
            for (int i = 0; i < count; ++i) {
                deviceInfoList.add(new DeviceInfo(kind));
            }
            waitingQueue = new LinkedList<>();
        }

        List<DeviceInfo> getDeviceInfoList() {
            return deviceInfoList;
        }

        Queue<RequestInfo> getWaitingQueue() {
            return waitingQueue;
        }
    }

    public static class Snapshot {
        private int ID;
        private Status status;
        private int PID;

        public Snapshot(int ID, Status status, int PID) {
            this.ID = ID;
            this.status = status;
            this.PID = PID;
        }

        public int getID() {
            return ID;
        }

        public Status getStatus() {
            return status;
        }

        public int getPID() {
            return PID;
        }

        public enum Status {
            IDLE,
            BUSY
        }
    }

    private class DeviceTimerTask extends java.util.TimerTask {

        @Override
        public void run() {
            synchronized (DeviceManager.this) {
                for (DeviceList list : deviceListMap.values()) {
                    RequestInfo head = list.getWaitingQueue().peek();
                    for (DeviceInfo deviceInfo : list.getDeviceInfoList()) {
                        if (!deviceInfo.isIdle()) {
                            deviceInfo.restTime -= REST_TIME_INTERVAL;
                            if (deviceInfo.restTime <= 0) {
                                finishedHandlers.forEach(handler -> handler.handler(deviceInfo));
                                finishedQueue.add(deviceInfo.getPCB());
                                deviceInfo.release();
                            }
                        }
                        if (head != null && deviceInfo.isIdle()) {
                            head = list.getWaitingQueue().poll();
                            deviceInfo.alloc(head.getPCB(), head.getTime());
                            head = list.getWaitingQueue().peek();
                        }
                    }
                }
            }
        }
    }
}
