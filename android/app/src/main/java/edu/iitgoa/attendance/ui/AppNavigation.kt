package edu.iitgoa.attendance.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import edu.iitgoa.attendance.data.AuthState
import edu.iitgoa.attendance.data.CurrentUser
import edu.iitgoa.attendance.data.repo.SessionSlot
import edu.iitgoa.attendance.data.repo.slot
import edu.iitgoa.attendance.ui.auth.AuthViewModel
import edu.iitgoa.attendance.ui.auth.LoginScreen
import edu.iitgoa.attendance.ui.auth.SignupScreen
import edu.iitgoa.attendance.ui.common.LoadingBox
import edu.iitgoa.attendance.ui.profile.ProfileScreen
import edu.iitgoa.attendance.ui.profile.ProfileViewModel
import edu.iitgoa.attendance.ui.student.CourseSessionsScreen
import edu.iitgoa.attendance.ui.student.CourseSessionsViewModel
import edu.iitgoa.attendance.ui.student.FaceEnrollScreen
import edu.iitgoa.attendance.ui.student.FaceEnrollViewModel
import edu.iitgoa.attendance.ui.student.MarkAttendanceScreen
import edu.iitgoa.attendance.ui.student.MarkAttendanceViewModel
import edu.iitgoa.attendance.ui.student.StudentHomeScreen
import edu.iitgoa.attendance.ui.student.StudentHomeViewModel
import edu.iitgoa.attendance.ui.teacher.SessionAttendanceScreen
import edu.iitgoa.attendance.ui.teacher.SessionAttendanceViewModel
import edu.iitgoa.attendance.ui.teacher.TeacherCourseScreen
import edu.iitgoa.attendance.ui.teacher.TeacherCourseViewModel
import edu.iitgoa.attendance.ui.teacher.TeacherHomeScreen
import edu.iitgoa.attendance.ui.teacher.TeacherHomeViewModel
import edu.iitgoa.attendance.ui.today.TodayScreen
import edu.iitgoa.attendance.ui.today.TodayViewModel

private object Route {
    const val TODAY = "today"
    const val PROFILE = "profile"
    const val LOGIN = "login"
    const val SIGNUP = "signup"

    const val STUDENT_HOME = "student"
    const val FACE_ENROLL = "faceEnroll"
    const val STUDENT_COURSE = "studentCourse/{course}"
    const val MARK = "mark/{course}/{date}/{start}/{end}"

    const val TEACHER_HOME = "teacher"
    const val TEACHER_COURSE = "teacherCourse/{course}"
    const val SESSION = "session/{course}/{date}/{start}/{end}"

    fun studentCourse(course: String) = "studentCourse/${course.enc()}"
    fun courseHomeFor(user: CurrentUser) =
        if (user.canManageCourses) TEACHER_HOME else STUDENT_HOME
    fun teacherCourse(course: String) = "teacherCourse/${course.enc()}"
    fun mark(slot: SessionSlot) = "mark/${slot.path()}"
    fun session(slot: SessionSlot) = "session/${slot.path()}"

    private fun SessionSlot.path() =
        "${courseName.enc()}/${date.enc()}/${startTime.enc()}/${endTime.enc()}"

    /** Course names and times contain spaces and colons, so encode them. */
    private fun String.enc(): String = Uri.encode(this)
}

@Composable
fun AttendanceApp(modifier: Modifier = Modifier) {
    val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory)
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val authUi by authViewModel.ui.collectAsStateWithLifecycle()

    when (val state = authState) {
        AuthState.Loading -> LoadingBox(modifier)

        AuthState.SignedOut -> AuthNavHost(
            state = authUi,
            onLogin = authViewModel::login,
            onRegister = authViewModel::register,
            onLeaveScreen = authViewModel::dismissError,
            modifier = modifier,
        )

        is AuthState.SignedIn -> SignedInNavHost(
            user = state.user,
            onLogout = authViewModel::logout,
            onProfileChanged = authViewModel::refreshProfile,
            modifier = modifier,
        )
    }
}

@Composable
private fun AuthNavHost(
    state: edu.iitgoa.attendance.ui.auth.AuthUiState,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String, String, String) -> Unit,
    onLeaveScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()

    NavHost(nav, startDestination = Route.LOGIN, modifier = modifier) {
        composable(Route.LOGIN) {
            LoginScreen(
                state = state,
                onLogin = onLogin,
                onGoToSignup = {
                    onLeaveScreen()
                    nav.navigate(Route.SIGNUP)
                },
            )
        }
        composable(Route.SIGNUP) {
            SignupScreen(
                state = state,
                onRegister = onRegister,
                onGoToLogin = {
                    onLeaveScreen()
                    nav.popBackStack()
                },
            )
        }
    }
}

@Composable
private fun SignedInNavHost(
    user: CurrentUser,
    onLogout: () -> Unit,
    onProfileChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    val coursesRoute = Route.courseHomeFor(user)

    // Only the two top-level destinations get a bottom bar. Detail screens are
    // pushed on top of it, so the bar does not follow you into a camera view.
    val topLevel = setOf(Route.TODAY, coursesRoute)
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (currentRoute in topLevel) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Route.TODAY,
                        onClick = { nav.switchTopLevel(Route.TODAY) },
                        icon = { Icon(Icons.Default.Today, contentDescription = null) },
                        label = { Text("Today") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == coursesRoute,
                        onClick = { nav.switchTopLevel(coursesRoute) },
                        icon = { Icon(Icons.Default.School, contentDescription = null) },
                        label = { Text("Courses") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = Route.TODAY,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.PROFILE) {
                val vm: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory)
                val state by vm.state.collectAsStateWithLifecycle()

                ProfileScreen(
                    user = user,
                    state = state,
                    onSave = vm::save,
                    onEnrollFace = { nav.navigate(Route.FACE_ENROLL) },
                    onRemoveFace = vm::removeFace,
                    onLogout = onLogout,
                    onBack = { nav.popBackStack() },
                    onMessagesShown = vm::clearMessages,
                )
            }

            composable(Route.TODAY) {
                val vm: TodayViewModel = viewModel(factory = TodayViewModel.Factory)
                val state by vm.state.collectAsStateWithLifecycle()

                TodayScreen(
                    user = user,
                    state = state,
                    onRefresh = vm::refresh,
                    onOpenProfile = { nav.navigate(Route.PROFILE) },
                    onOpenSession = { session ->
                        // A teacher opens the register; a student who can mark
                        // goes straight to the camera, otherwise to the course.
                        val route = when {
                            user.canManageCourses -> Route.session(session.slot())
                            session.isOpen && !user.needsFaceEnrollment ->
                                Route.mark(session.slot())
                            else -> Route.studentCourse(session.courseName)
                        }
                        nav.navigate(route)
                    },
                )
            }

            studentGraph(nav, user, onLogout, onProfileChanged)
            teacherGraph(nav, user, onLogout)
        }
    }
}

/** Standard bottom-nav behaviour: single copy, state preserved, no back pile-up. */
private fun NavHostController.switchTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun androidx.navigation.NavGraphBuilder.studentGraph(
    nav: NavHostController,
    user: CurrentUser,
    onLogout: () -> Unit,
    onProfileChanged: () -> Unit,
) {
    composable(Route.STUDENT_HOME) {
        val vm: StudentHomeViewModel = viewModel(factory = StudentHomeViewModel.Factory)
        val state by vm.state.collectAsStateWithLifecycle()

        StudentHomeScreen(
            user = user,
            state = state,
            onOpenCourse = { nav.navigate(Route.studentCourse(it)) },
            onEnroll = vm::enroll,
            onEnrollFace = { nav.navigate(Route.FACE_ENROLL) },
            onOpenProfile = { nav.navigate(Route.PROFILE) },
            onMessagesShown = vm::clearMessages,
        )
    }

    composable(Route.FACE_ENROLL) {
        val vm: FaceEnrollViewModel = viewModel(factory = FaceEnrollViewModel.Factory)
        val state by vm.state.collectAsStateWithLifecycle()

        FaceEnrollScreen(
            state = state,
            onSubmit = vm::submit,
            onDone = {
                onProfileChanged()
                nav.popBackStack()
            },
            onBack = { nav.popBackStack() },
        )
    }

    composable(Route.STUDENT_COURSE) { entry ->
        val course = entry.arguments?.getString("course").orEmpty()
        val vm: CourseSessionsViewModel =
            viewModel(factory = CourseSessionsViewModel.factory(course))
        val state by vm.state.collectAsStateWithLifecycle()

        CourseSessionsScreen(
            courseName = course,
            state = state,
            // Without a reference face the server would refuse with 428, so
            // the button explains that instead of failing after a capture.
            canMarkAttendance = !user.needsFaceEnrollment,
            onMark = { nav.navigate(Route.mark(it.slot())) },
            onBack = { nav.popBackStack() },
        )
    }

    composable(Route.MARK) { entry ->
        val slot = entry.slot()
        val vm: MarkAttendanceViewModel =
            viewModel(factory = MarkAttendanceViewModel.factory(slot))
        val state by vm.state.collectAsStateWithLifecycle()

        MarkAttendanceScreen(
            courseName = slot.courseName,
            state = state,
            onSubmit = vm::submit,
            onRetry = vm::reset,
            onDone = { nav.popBackStack() },
            onBack = { nav.popBackStack() },
        )
    }
}

private fun androidx.navigation.NavGraphBuilder.teacherGraph(
    nav: NavHostController,
    user: CurrentUser,
    onLogout: () -> Unit,
) {
    composable(Route.TEACHER_HOME) {
        val vm: TeacherHomeViewModel = viewModel(factory = TeacherHomeViewModel.Factory)
        val state by vm.state.collectAsStateWithLifecycle()

        TeacherHomeScreen(
            user = user,
            state = state,
            onOpenCourse = { nav.navigate(Route.teacherCourse(it)) },
            onCreateCourse = vm::createCourse,
            onDeleteCourse = vm::deleteCourse,
            onSetArchived = vm::setArchived,
            onToggleShowArchived = vm::toggleShowArchived,
            onOpenProfile = { nav.navigate(Route.PROFILE) },
            onMessagesShown = vm::clearMessages,
        )
    }

    composable(Route.TEACHER_COURSE) { entry ->
        val course = entry.arguments?.getString("course").orEmpty()
        val vm: TeacherCourseViewModel =
            viewModel(factory = TeacherCourseViewModel.factory(course))
        val state by vm.state.collectAsStateWithLifecycle()

        TeacherCourseScreen(
            courseName = course,
            state = state,
            onOpenSession = { nav.navigate(Route.session(it.slot())) },
            onCreateSession = vm::createSession,
            onDeleteSession = vm::deleteSession,
            onBack = { nav.popBackStack() },
            onMessagesShown = vm::clearMessages,
        )
    }

    composable(Route.SESSION) { entry ->
        val slot = entry.slot()
        val vm: SessionAttendanceViewModel =
            viewModel(factory = SessionAttendanceViewModel.factory(slot))
        val state by vm.state.collectAsStateWithLifecycle()

        SessionAttendanceScreen(
            courseName = slot.courseName,
            date = slot.date,
            startTime = slot.startTime,
            endTime = slot.endTime,
            state = state,
            onMarkManually = vm::markManually,
            onRemove = vm::remove,
            onBack = { nav.popBackStack() },
            onMessagesShown = vm::clearMessages,
        )
    }
}

private fun androidx.navigation.NavBackStackEntry.slot() = SessionSlot(
    courseName = arguments?.getString("course").orEmpty(),
    date = arguments?.getString("date").orEmpty(),
    startTime = arguments?.getString("start").orEmpty(),
    endTime = arguments?.getString("end").orEmpty(),
)
