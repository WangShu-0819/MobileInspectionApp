package com.wearable.inspection.mobile.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.wearable.inspection.mobile.ui.BottomNavItem
import com.wearable.inspection.mobile.ui.theme.LocalCustomColors

@Composable
fun BottomNavigationBar(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        BottomNavItem.LiveInspection,
        BottomNavItem.TraceRecords,
        BottomNavItem.Profile,
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val customColors = LocalCustomColors.current

    NavigationBar(
        modifier = modifier,
        containerColor = customColors.bottomNavBackground,
        contentColor = customColors.bottomNavContent,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets.navigationBars
    ) {
        items.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true

            NavigationBarItem(
                icon = {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (selected) customColors.primary else customColors.bottomNavInactive
                    )
                },
                label = {
                    Text(
                        item.label,
                        color = if (selected) customColors.primary else customColors.bottomNavInactive
                    )
                },
                selected = selected,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = customColors.primary,
                    selectedTextColor = customColors.primary,
                    indicatorColor = customColors.bottomNavSelectedBg,
                    unselectedIconColor = customColors.bottomNavInactive,
                    unselectedTextColor = customColors.bottomNavInactive
                )
            )
        }
    }
}
