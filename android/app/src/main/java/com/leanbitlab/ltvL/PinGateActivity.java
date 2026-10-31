package com.omeda.arc;

/**
 * The PIN screen in front of a locked app. Same screen as PinActivity, but in a task of its own
 * (see the manifest), so that after the right PIN it simply goes away and the app it covered shows again,
 * instead of bringing Arc's home screen forward.
 */
public class PinGateActivity extends PinActivity {
}
