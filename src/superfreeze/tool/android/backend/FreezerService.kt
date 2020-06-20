/*
Copyright (c) 2018 Hocuri

This file is part of SuperFreezZ.

SuperFreezZ is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

SuperFreezZ is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with SuperFreezZ.  If not, see <http://www.gnu.org/licenses/>.
*/

package superfreeze.tool.android.backend

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import superfreeze.tool.android.database.prefUseAccessibilityService
import superfreeze.tool.android.expectNonNull

/**
 * This is the Accessibility service class, responsible to automatically freeze apps.
 */
class FreezerService : AccessibilityService() {

	private val forceStopButtonName by lazy {
		try {
			// Try to find out what it says on the Force Stop button (different in different languages)
			val resourcesPackageName = "com.android.settings"
			val resources = applicationContext.packageManager.getResourcesForApplication(resourcesPackageName)
			val resourceId = resources.getIdentifier("force_stop", "string", resourcesPackageName)
			if (resourceId > 0) {
				resources.getString(resourceId)
			} else {
				Log.e(TAG, "Label for the force stop button in settings could not be found")
				null
			}
		} catch (e: PackageManager.NameNotFoundException) {
			Log.e(TAG, "Settings activity's resources not found")
			null
		}
	}

	private enum class NextAction {
		PRESS_FORCE_STOP, PRESS_OK, PRESS_BACK, DO_NOTHING
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent) {

		//Does not work on older versions of Android.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
			return
		}

		//We are only interested in WINDOW_STATE_CHANGED events
		if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
			return
		}

		when (nextAction) {
			NextAction.DO_NOTHING -> { }

			NextAction.PRESS_FORCE_STOP -> {
				if (event.className == "com.android.settings.applications.InstalledAppDetailsTop") {
					pressForceStopButton(event)
				} else {
					Log.w(TAG, "awaited InstalledAppDetailsTop to be the next screen but it was ${event.className}")
					wrongScreenShown()
				}
			}

			NextAction.PRESS_OK -> {
				if (event.className == "android.app.AlertDialog" || event.className == "androidx.appcompat.app.AlertDialog") {
					pressOkButton(event)
				} else {
					Log.w(TAG, "awaited AlertDialog to be the next screen but it was ${event.className}")
					wrongScreenShown()
				}
			}

			NextAction.PRESS_BACK -> {
				pressBackButton()
			}
		}
	}

	private fun wrongScreenShown() {
		// If the last action was more than 8 seconds ago, something went wrong and we should stop not to destroy anything.
		if (System.currentTimeMillis() - lastActionTimestamp > 8000) {
			Log.e(
				TAG,
				"An unexpected screen turned up and the last action was more than 8 seconds ago. Something went wrong. Aborted not to destroy anything"
			)
			stopAnyCurrentFreezing() // Stop everything, it is to late to do anything :-(
		}
		// else do nothing and simply wait for the next screen to show up.
	}

	override fun onInterrupt() {
	}

	@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
	private fun pressForceStopButton(event: AccessibilityEvent) {
		val node = event.source.expectNonNull(TAG) ?: return
		
		var nodesToClick = node.findAccessibilityNodeInfosByText("FORCE STOP")

		if (nodesToClick.isEmpty())
			nodesToClick = node.findAccessibilityNodeInfosByViewId("com.android.settings:id/force_stop_button")

		if (nodesToClick.isEmpty())
			nodesToClick = node.findAccessibilityNodeInfosByText(forceStopButtonName)

		if (nodesToClick.isEmpty()) {
			val buttons = ArrayList<AccessibilityNodeInfo>()
			getButtons(node, buttons)
			if (buttons.size == 2) {
				val rightNode = buttons[1]
				val leftNode = buttons[0]
				Log.w(TAG, "right: $rightNode, left: $leftNode")

				// "force stop" contains a space, "uninstall" does not. So, try to find out which button is which by counting the spaces.
				val spacesRight = rightNode.text?.trim()?.count { it == ' ' }
				val spacesLeft = leftNode.text?.trim()?.count { it == ' ' }

				if (spacesLeft == 1 && spacesRight == 0)
					nodesToClick = listOf(leftNode)
				else if (spacesLeft == 0 && spacesRight == 1)
					nodesToClick = listOf(rightNode)
			}
		}

		val success = clickAll(nodesToClick, "force stop", node)
		if (success) nextAction = NextAction.PRESS_OK

		node.recycle()
	}


	@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
	private fun pressOkButton(event: AccessibilityEvent) {
		val node = event.source.expectNonNull(TAG) ?: return

		var nodesToClick = node.findAccessibilityNodeInfosByText(getString(android.R.string.ok))

		if (nodesToClick.isEmpty())
			nodesToClick = node.findAccessibilityNodeInfosByText(forceStopButtonName)
			// Apparently necessary sometimes, see https://gitlab.com/SuperFreezZ/SuperFreezZ/issues/43

		val success = clickAll(nodesToClick, "OK", node)
		if (success) nextAction = NextAction.PRESS_BACK

		node.recycle()
	}


	@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
	private fun pressBackButton() {
		nextAction = NextAction.DO_NOTHING

		performGlobalAction(GLOBAL_ACTION_BACK)

		Log.i(TAG, "We arrived at pressBackButton(), this means that one app was successfully frozen.")
	}

	/**
	 * Clicks all nodes. If it succeeds, it returns true. If it does not succeed, it handles this by
	 * itself, sets the nextAction to what makes sense and returns true.
	 */
	@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
	private fun clickAll(nodes: List<AccessibilityNodeInfo>, buttonName: String, parentNode: AccessibilityNodeInfo): Boolean {

		if (nodes.isEmpty()) {
			Log.e(TAG, "Could not find the $buttonName button.")
			Log.e(TAG, "Buttons:")
			printNodes(parentNode)
			stopAnyCurrentFreezing()
			doOnAppCouldNotBeFrozen?.invoke(this)
			return false
		} else if (nodes.size > 1) {
			Log.w(TAG, "Found more than one $buttonName button, clicking them all.")
		}

		val clickableNodes = nodes.filter { it.isClickable && it.isEnabled }

		if (clickableNodes.isEmpty()) {
			Log.e(TAG, "The button(s) is/are not clickable, aborting.")
			// Just do not press the button but immediately press Back and act as if the app was successfully frozen:
			// A disabled or not clickable button probably means that the app already is frozen.
			pressBackButton()
			return false
		}

		for (node in clickableNodes) {
			node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
		}

		notifyThereIsStillMovement(this)

		return true
	}

	private fun printNodes(parentNode: AccessibilityNodeInfo) {
		if (parentNode.isClickable) {
			Log.e(TAG, "    $parentNode")
		}
		for (i in 0 until parentNode.childCount) {
			printNodes(parentNode.getChild(i))
		}
	}

	private fun getButtons(
		parentNode: AccessibilityNodeInfo,
		l: ArrayList<AccessibilityNodeInfo>) {
		if (parentNode.isClickable && parentNode.className.split(".").last() == "Button") {
			l.add(parentNode)
		}
		for (i in 0 until parentNode.childCount) {
			getButtons(parentNode.getChild(i), l)
		}
	}

	private var screenReceiver: BroadcastReceiver? = null

	override fun onServiceConnected() {
		isEnabled = true

		// From now on, expect that the service works:
		prefUseAccessibilityService = true

		if (screenReceiver == null) {
			screenReceiver = freezeOnScreenOff_init(this, screenLockerFunction = {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
					performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
				}
			})
		}
	}

	override fun onDestroy() {
		Log.i(TAG, "FreezerService was destroyed.")
		isEnabled = false
		if (screenReceiver != null) unregisterReceiver(screenReceiver)
		stopAnyCurrentFreezing()
	}

	companion object {

		var doOnAppCouldNotBeFrozen: ((Context) -> Unit)? = null
		private var nextAction = NextAction.DO_NOTHING
		/**
		 * The timestamp of the last action. This can be clicking a button or clickFreezeButtons() being called.
		 */
		private var lastActionTimestamp = 0L
		var isEnabled = false
			private set

		private val timeoutHandler = Handler()

		/**
		 * Clicks the "Force Stop", the "OK" and the "Back" button when the corresponding screen turns up.
		 * Call this BEFORE starting the settings about the app you want to freeze!
		 */
		@RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
		fun clickFreezeButtons(context: Context) {
			if (nextAction == NextAction.DO_NOTHING) {
				nextAction = NextAction.PRESS_FORCE_STOP
				notifyThereIsStillMovement(context)
			} else {
				throw IllegalStateException("Attempted to freeze, but was still busy (nextAction was $nextAction)")
			}
		}

		fun finishedFreezing() {
			timeoutHandler.removeCallbacksAndMessages(null)
		}

		fun isBusy() = (nextAction != NextAction.DO_NOTHING)

		private fun notifyThereIsStillMovement(context: Context) {
			timeoutHandler.removeCallbacksAndMessages(null)

			// After 4 seconds, assume that something went wrong
			timeoutHandler.postDelayed({
				Log.w(TAG, "timeout")
				stopAnyCurrentFreezing()
				doOnAppCouldNotBeFrozen?.invoke(context)
			}, 4000)

			lastActionTimestamp = System.currentTimeMillis()
		}

		/**
		 * Cleans up when no more apps shall be frozen or before onAppCouldNotBeFrozen() is called
		 * (in the latter case, onAppCouldNotBeFrozen() will care about restarting freeze)
		 */
		fun stopAnyCurrentFreezing() {
			if (nextAction != NextAction.DO_NOTHING)
				Log.i(TAG, "Stopping current freeze process (stopAnyCurrentFreezing()), nextAction was $nextAction")
			nextAction = NextAction.DO_NOTHING
			timeoutHandler.removeCallbacksAndMessages(null)
		}

	}

}

private const val TAG = "SF-FreezerService"
