/*
Copyright (c) 2018,2019 Hocuri
Copyright (c) 2019 Robin Naumann

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


package superfreeze.tool.android.userInterface.intro

import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.github.paolorotolo.appintro.AppIntro
import com.github.paolorotolo.appintro.AppIntroFragment
import com.github.paolorotolo.appintro.model.SliderPagerBuilder
import superfreeze.tool.android.BuildConfig
import superfreeze.tool.android.R
import superfreeze.tool.android.backend.FreezerService
import superfreeze.tool.android.database.prefIntroAlreadyShown
import superfreeze.tool.android.userInterface.MyActivityCompanion

/**
 * Shows the intro slides on the very first startup
 */
class IntroActivity : AppIntro() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val actionBar = supportActionBar
		actionBar?.hide()

		val action = intent.action
		if (INTRO_SHOW_ACCESSIBILITY_SERVICE_CHOOSER == action) {

			addSlide(AccessibilityServiceChooserFragment())

		} else {

			addSlide(WelcomeFragment())
			addSlide(
				AppIntroFragment.newInstance(
					SliderPagerBuilder()
						.title(getString(R.string.warning))
						.description(getString(R.string.warning_long_1))
						.imageDrawable(R.drawable.ic_warning)
						.bgColor(ContextCompat.getColor(this, R.color.warning))
						.build()
				)
			)
			addSlide(
				AppIntroFragment.newInstance(
					SliderPagerBuilder()
						.title(getString(R.string.info))
						.description(getString(R.string.warning_long_2))
						.imageDrawable(R.drawable.ic_warning)
						.bgColor(ContextCompat.getColor(this, R.color.warning))
						.build()
				)
			)
			addSlide(IntroModesFragment())
			addSlide(AccessibilityServiceChooserFragment())
		}

		showSkipButton(false)
		setDoneText("")

	}

	//If the user presses the back button it tends to break the AppIntro route logic
	override fun onBackPressed() {
		//do nothing
	}

	private var lastSlide = false
	override fun onResume() {
		super.onResume()
		if (lastSlide && FreezerService.isEnabled) {
			Log.i(TAG, "Done on resume activity")
			done()
		}
	}

	override fun onSlideChanged(oldFragment: Fragment?, newFragment: Fragment?) {
		super.onSlideChanged(oldFragment, newFragment)
		if (newFragment is AccessibilityServiceChooserFragment) {
			lastSlide = true
			if (FreezerService.isEnabled) {
				Log.i(TAG, "Done on slide changed")
				done()
			}
		}
	}

	override fun onDonePressed(currentFragment: Fragment?) {
		super.onDonePressed(currentFragment)
		Log.i(TAG, "Done on pressed")
		done()
	}

	internal fun done() {
		prefIntroAlreadyShown = false
		finish()
	}

}

private const val TAG = "SF-IntroActivity"

const val INTRO_SHOW_ACCESSIBILITY_SERVICE_CHOOSER =
	BuildConfig.APPLICATION_ID + "SHOW_ACCESSIBILITY_SERVICE_CHOOSER"

