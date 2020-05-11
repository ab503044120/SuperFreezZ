/*
The MIT License (MIT)

Copyright (c) 2015 axxapy
Copyright (c) 2018 Hocuri

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

package superfreeze.tool.android.userInterface.mainActivity

import android.app.usage.UsageStats
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import superfreeze.tool.android.AsyncDelegated
import superfreeze.tool.android.R
import superfreeze.tool.android.backend.getAllAggregatedUsageStats
import superfreeze.tool.android.backend.getRecentAggregatedUsageStats
import superfreeze.tool.android.backend.getSortByFreezeStateComparator
import superfreeze.tool.android.backend.isSystemApp
import superfreeze.tool.android.database.FreezeMode
import superfreeze.tool.android.userInterface.toast
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList


/**
 * This class is responsible for viewing the list of installed apps.
 */
class AppsListAdapter internal constructor(
	internal val mainActivity: MainActivity,
	internal var sortModeIndex: Int
) : RecyclerView.Adapter<AbstractViewHolder>() {

	val colorFilterGrey = colorFilter(R.color.button_greyed_out)
	val colorFilters by lazy {
		mapOf(
			FreezeMode.ALWAYS to colorFilter(R.color.always_freeeze),
			FreezeMode.NEVER to colorFilter(R.color.never_freeeze),
			FreezeMode.WHEN_INACTIVE to colorFilter(R.color.inactive_freeeze)
		)
	}

	private fun colorFilter(color: Int): PorterDuffColorFilter {
		return PorterDuffColorFilter(
			ContextCompat.getColor(mainActivity, color),
			PorterDuff.Mode.SRC_ATOP
		)
	}

	internal val usageStatsMap: Map<String, UsageStats>? by AsyncDelegated {
		getRecentAggregatedUsageStats(mainActivity)
	}


	/**
	 * This list contains all apps in the list exactly once. That is, apps that appear twice in the list are contained only once.
	 */
	private val appsList = ArrayList<ListItemApp>()

	/**
	 * This list contains all apps and the section headers, as shown to the user when not searching.
	 */
	private var originalList = emptyList<AbstractListItem>()

	/**
	 * This list contains the items as shown to the user, including section headers. While the user is not searching, this is a clone of originalList.
	 */
	internal var list = emptyList<AbstractListItem>()


	internal val packageManager: PackageManager = mainActivity.applicationContext.packageManager

	internal val cacheAppName = ConcurrentHashMap<String, String>()
	internal val cacheAppIcon = ConcurrentHashMap<String, Drawable>()

	var searchPattern: String = ""
		set(value) {
			field = value.toLowerCase(Locale.ROOT)
			refreshList()
			notifyDataSetChanged()
		}


	override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): AbstractViewHolder {
		return if (i == 0) {
			ViewHolderApp(
				LayoutInflater.from(viewGroup.context).inflate(
					R.layout.list_item,
					viewGroup,
					false
				),
				viewGroup.context,
				this
			)
		} else {
			ViewHolderSectionHeader(
				LayoutInflater.from(viewGroup.context).inflate(
					R.layout.list_section_header,
					viewGroup,
					false
				)
			)
		}
	}

	override fun onBindViewHolder(holder: AbstractViewHolder, i: Int) {
		holder.bindTo(list[i])
	}

	override fun getItemCount(): Int {
		return list.size
	}

	override fun getItemViewType(position: Int): Int {
		return list[position].type
	}


	internal fun setAndLoadItems(packages: List<PackageInfo>) {
		appsList.clear()
		appsList.addAll(packages.map {
			ListItemApp(it.packageName, this)
		})


		@Suppress("UNCHECKED_CAST")
		loadAllNames(appsList) {
			mainActivity.runOnUiThread {
				sortList()
				refreshBothLists()
				notifyDataSetChanged()
				mainActivity.hideProgressBar()
				Log.v(TAG, "Fully drawn")
			}
		}
	}

	internal fun sortList() {
		Collections.sort(appsList, listComparator(sortModeIndex))
	}

	internal fun deleteAppInfos() {
		for (app in appsList) {
			app.deleteAppInfo()
		}
	}

	internal fun refresh() {
		//We need to test whether the applications are still installed and remove those that are not.
		//Apparently, there is no better way for this than trying to access the applicationInfo.
		appsList.removeAll {
			try {
				it.applicationInfo
				false
			} catch (e: PackageManager.NameNotFoundException) {
				true
			}
		}

		refreshBothLists()
		notifyDataSetChanged()
	}

	internal fun trimMemory() {
		cacheAppIcon.clear()
	}


	// "Both lists" means originalList and list:
	@Suppress("UNCHECKED_CAST")
	internal fun refreshBothLists() {

		val listPendingFreeze =
			appsList.filter {
				it.isPendingFreeze()
			}

		val newOriginalList = ArrayList<AbstractListItem>((appsList.size * 1.5).toInt())


		if (listPendingFreeze.isEmpty()) {
			newOriginalList.add(
				ListItemSectionHeader(mainActivity.getString(R.string.no_apps_pending_freeze))
			)
		} else {
			newOriginalList.add(
				ListItemSectionHeader(mainActivity.getString(R.string.pending_freeze))
			)
			newOriginalList.addAll(listPendingFreeze)
		}


		when (sortModeIndex) {
			1 -> {// 1 means sort by freeze state, see gitlab.com/SuperFreezZ/SuperFreezZ/issues/48
				newOriginalList.add(
					ListItemSectionHeader(mainActivity.getString(R.string.other_apps))
				)
				newOriginalList.addAll(appsList.filter { !it.isPendingFreeze() })

			}
			3 -> { // 3 means sort by user/system
				newOriginalList.add(
					ListItemSectionHeader(mainActivity.getString(R.string.user_apps))
				)
				newOriginalList.addAll(appsList.filter { !isSystemApp(it.applicationInfo) })

				newOriginalList.add(
					ListItemSectionHeader(mainActivity.getString(R.string.system_apps))
				)
				newOriginalList.addAll(appsList.filter { isSystemApp(it.applicationInfo) })

			}
			else -> {
				newOriginalList.add(
					ListItemSectionHeader(mainActivity.getString(R.string.all_apps))
				)
				newOriginalList.addAll(appsList)
			}
		}

		mainActivity.runOnUiThread {
			originalList = newOriginalList
			refreshList()
		}
	}

	private fun refreshList() {
		list =
			if (searchPattern.isEmpty()) {
				originalList
			} else {

				// When the user is searching, the more relevant apps (that is, those
				// that start with the search pattern) are shown at the top:
				val (importantApps, otherApps) =
					appsList
						.asSequence()
						.filter { it.isMatchingSearchPattern() }
						.partition {
							it.text.toLowerCase(Locale.ROOT).startsWith(searchPattern)
						}
				importantApps + otherApps

			}
	}

	private fun loadAllNames(items: List<ListItemApp>, onAllNamesLoaded: () -> Unit) {
		GlobalScope.launch {
			items.map { item ->
				GlobalScope.async {
					cacheAppName[item.packageName] =
						item.applicationInfo.loadLabel(packageManager).toString()
				}
			}.joinAll()
			onAllNamesLoaded()
		}
	}

	private fun listComparator(index: Int): Comparator<ListItemApp> = when (index) {

		// 0: Sort by name
		0 -> compareBy {
			it.text.toLowerCase(Locale.getDefault())
		}

		// 1: Sort by freeze state
		1 -> getSortByFreezeStateComparator(usageStatsMap, mainActivity)

		// 2: Sort by last time used
		2 -> {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
				mainActivity.toast(
					"Last time used is not available for your Android version",
					Toast.LENGTH_LONG
				)
				compareBy { it.text }
			} else {
				mainActivity.toast(
					mainActivity.getString(R.string.sort_last_time_used_explanation),
					Toast.LENGTH_LONG
				)
				val allUsageStats = getAllAggregatedUsageStats(mainActivity)
				compareBy {
					allUsageStats?.get(it.packageName)?.lastTimeUsed ?: 0L
				}
			}
		}

		// 3: Sort by user/system app install type
		3 -> compareBy {
			isSystemApp(it.applicationInfo)
		}

		else -> throw IllegalArgumentException("sort dialog index should have been a number from 0-3")
	}
}


private const val TAG = "SF-AppsListAdapter"
