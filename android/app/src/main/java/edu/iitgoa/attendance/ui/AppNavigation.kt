package edu.iitgoa.attendance.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.iitgoa.attendance.data.AuthState
import edu.iitgoa.attendance.data.CurrentUser
import edu.iitgoa.attendance.data.repo.SessionSlot
import edu.iitgoa.attendance.data.repo.slot
import edu.iitgoa.attendance.ui.auth.AuthViewModel
import edu.iitgoa.attendance.ui.auth.LoginScreen
import edu.iitgoa.attendance.ui.auth.SignupScreen
import edu.iitgoa.attendance.ui.common.LoadingBox
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

private object Route {
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
    val start = if (user.canManageCourses) Route.TEACHER_HOME else Route.STUDENT_HOME

    NavHost(nav, startDestination = start, modifier = modifier) {
        studentGraph(nav, user, onLogout, onProfileChanged)
        teacherGraph(nav, user, onLogout)
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
            onLogout = onLogout,
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
            onLogout = onLogout,
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
