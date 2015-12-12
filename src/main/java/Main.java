import com.pi4j.io.gpio.*;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import org.parse4j.Parse;
import org.parse4j.ParseException;
import org.parse4j.ParseObject;
import org.parse4j.ParseQuery;
import org.parse4j.callback.FindCallback;
import org.parse4j.callback.SaveCallback;

import java.util.*;

public class Main {

    //Lock bouncing period
    public static final int DELAY = 2000;

    //How often raspberry requests updated from cloud
    public static final int POLLING_PERIOD = 2000;

    // ================
    // Parse fields
    // ================
    public final String PROJECTOR_CONTROL = "ProjectorControl";
    public static final String MOBILE = "Mobile";
    public static final String PI = "Pi";
    public static final String STATUS = "status";
    public static final String LOCK_STATE = "lockState";
    public static final String LOCK_OPENED = "Unlocked";
    public static final String LOCK_CLOSED = "Locked";
    public static final String PROJECTOR_ON = "On";
    public static final String PROJECTOR_OFF = "Off";
    public static final String UPDATED_FROM = "updatedFrom";
    public static final String CREATED_AT = "createdAt";

    // ================
    // GPIO block
    // ================
    final GpioController gpio = GpioFactory.getInstance();
    final GpioPinDigitalInput myButton = gpio.provisionDigitalInputPin(RaspiPin.GPIO_02, PinPullResistance.PULL_DOWN);
    final GpioPinDigitalOutput switcher = gpio.provisionDigitalOutputPin(RaspiPin.GPIO_01, PinState.LOW);


    private PinState lockState = myButton.getState();
    private boolean projectorState = false;
    private Timer timer = new Timer();
    private TimerTask task;
    private Date lastCreatedAt = new Date(2013, 12, 12);

    public Main() {
        Parse.initialize("tXFcf53HS1hezSeE6UOUqpG4szPu0D2vRuoplWEy", "Yzn1No56GHkoXo9RoheSK11HuAGejMwUOjXqmYrC");
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        getLastSavedState();
        initializeButton();
        initializePolling();
        // Wait until user stops the application
        Scanner scanIn = new Scanner(System.in);
        String nextLine = "";
        while (true) {
            nextLine = scanIn.nextLine();
            if (nextLine.equals("!exit")) {
                System.exit(0);
            }
            ;
        }
    }

    private void getLastSavedState() {
        ParseQuery<ParseObject> query = ParseQuery.getQuery(PROJECTOR_CONTROL);
        query.orderByDescending(CREATED_AT);
        query.whereMatches(UPDATED_FROM, PI);
        query.limit(1);
        query.findInBackground(new FindCallback<ParseObject>() {
            public void done(List<ParseObject> scoreList, ParseException e) {
                if (e == null) {
                    for (ParseObject p : scoreList) {
                        lastCreatedAt = p.getCreatedAt();
                        projectorState = getProjectorState(p);
                        System.out.println(String.format("Last state was restored at %s, now projector %s", lastCreatedAt.toString(), getProjectorState(p)));
                    }
                } else {
                    System.out.println(String.format("Cannot get Users updates: %s", e.getMessage()));
                }
            }
        });
    }


    private boolean getProjectorState(ParseObject p) {
        if (PROJECTOR_ON.equals(p.get(STATUS))) {
            return true;
        }
        return false;
    }

    private String getProjectorState(boolean b) {
        if (b) {
            return PROJECTOR_ON;
        }
        return PROJECTOR_OFF;
    }


    private void initializePolling() {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                getLastChangedValue();
            }
        }, 5000, POLLING_PERIOD);
    }

    private void getLastChangedValue() {
        ParseQuery<ParseObject> query = ParseQuery.getQuery(PROJECTOR_CONTROL);
        query.whereGreaterThan(CREATED_AT, lastCreatedAt);
        query.orderByDescending(CREATED_AT);
        query.limit(1);
        query.findInBackground(new FindCallback<ParseObject>() {
            public void done(List<ParseObject> scoreList, ParseException e) {
                if (e == null) {
                    if (scoreList != null) {
                        for (ParseObject p : scoreList) {
                            if (MOBILE.equals(p.get(UPDATED_FROM))) {
                                lastCreatedAt = p.getCreatedAt();
                                if (PROJECTOR_ON.equals(p.get(STATUS))) {
                                    if (isLockOpened()) {
                                        projectorState = getProjectorState(p);
                                        sendOnSignalToProjector();
                                        sendOnNotificationToCloud();
                                        System.out.println(String.format("State was updated by User at %s, now projector %s", lastCreatedAt.toString(), getProjectorState(p)));
                                    }
                                }
                                ;
                                if (PROJECTOR_OFF.equals(p.get(STATUS))) {
                                    projectorState = getProjectorState(p);
                                    sendOffSignalToProjector();
                                    sendOffNotificationToCloud();
                                    System.out.println(String.format("State was updated by User at %s, now projector %s", lastCreatedAt.toString(), getProjectorState(p)));
                                }
                                ;
                            }
                            ;
                        }
                    }
                } else {
                    System.out.println(e.getMessage());
                }
            }
        });
    }

    private void initializeButton() {
        myButton.addListener(new GpioPinListenerDigital() {
            @Override
            public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
                if (isLockStateChanged(event)) {
                    if (isLockClosed()) {
                        resetTask();
                        task = new TimerTask() {
                            @Override
                            public void run() {
                                resetTask();
                                lockState = event.getState();
                                sentLockState();
                            }
                        };
                        timer.schedule(task, DELAY);
                    } else {
                        resetTask();
                        task = new TimerTask() {
                            @Override
                            public void run() {
                                resetTask();
                                lockState = event.getState();
                                sentLockState();
                            }
                        };
                        timer.schedule(task, DELAY);
                    }
                } else {
                    resetTask();
                }
            }

            private boolean isLockStateChanged(GpioPinDigitalStateChangeEvent event) {
                return !event.getState().equals(lockState);
            }


        });
    }

    private boolean isLockClosed() {
        return lockState.isLow();
    }

    private boolean isLockOpened() {
        return lockState.isHigh();
    }

    private void sentLockState() {
        if (isLockClosed()) {
            sendOffNotificationToCloud();
            sendOffSignalToProjector();
            System.out.println("Lock closed");
        } else {
            sendOnNotificationToCloud();
            System.out.println("Lock opened");
        }

    }

    private void sendOnSignalToProjector() {
        switcher.setState(PinState.LOW);
        switcher.setState(PinState.HIGH);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        switcher.setState(PinState.LOW);
        System.out.println("Projector switched on");
    }

    private void sendOffSignalToProjector() {
        if (projectorState) {
            switcher.setState(PinState.LOW);
            switcher.setState(PinState.HIGH);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            switcher.setState(PinState.LOW);
            System.out.println("Projector switched off");
        } else {
            System.out.println("Projector already switched off");
        }
    }

    private void sendOnNotificationToCloud() {
        ParseObject lock = new ParseObject(PROJECTOR_CONTROL);
        lock.put(STATUS, getProjectorState(projectorState));
        lock.put(UPDATED_FROM, PI);
        lock.put(LOCK_STATE, getLockState());
        lock.saveInBackground(new SaveCallback() {
            @Override
            public void done(ParseException parseException) {
                lastCreatedAt = lock.getCreatedAt();
                System.out.println("saveInBackground(): objectId: " + lock.getCreatedAt());
            }
        });
    }

    private String getLockState() {
        if (isLockOpened()) {
            return LOCK_OPENED;
        }
        return LOCK_CLOSED;
    }

    private void sendOffNotificationToCloud() {
        ParseObject lock = new ParseObject(PROJECTOR_CONTROL);
        lock.put(STATUS, PROJECTOR_OFF);
        lock.put(UPDATED_FROM, PI);
        lock.put(LOCK_STATE, getLockState());
        lock.saveInBackground(new SaveCallback() {
            @Override
            public void done(ParseException parseException) {
                lastCreatedAt = lock.getCreatedAt();
                System.out.println("saveInBackground(): objectId: " + lock.getCreatedAt());
            }
        });
    }

    private void resetTask() {
        if (task != null) {
            task.cancel();
        }
    }

    public static void main(String[] args) {
        new Main();
    }
}