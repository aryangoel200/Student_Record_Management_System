package edu.iitgoa.attendance.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import edu.iitgoa.attendance.AttendanceApp
import edu.iitgoa.attendance.data.AppContainer

/** Reaches the hand-rolled dependency graph from a ViewModel factory. */
val CreationExtras.container: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AttendanceApp)
        .container
