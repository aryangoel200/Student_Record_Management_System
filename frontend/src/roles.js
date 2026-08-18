/**
 * Role helpers.
 *
 * `role` is now one of "student" | "teacher" | "admin". It used to be a boolean
 * that also doubled as "has registered a face", which is why completing face
 * enrolment used to turn a student into a teacher. Face enrolment is tracked
 * separately, by `face_enrolled`.
 */

export const ROLES = {
    STUDENT: "student",
    TEACHER: "teacher",
    ADMIN: "admin",
};

export const isStudent = (person) => person?.role === ROLES.STUDENT;
export const isTeacher = (person) => person?.role === ROLES.TEACHER;
export const isAdmin = (person) => person?.role === ROLES.ADMIN;

/** Teachers and admins share every course-management screen. */
export const canManageCourses = (person) => isTeacher(person) || isAdmin(person);

/** Students must enrol a face before they can mark attendance. */
export const needsFaceEnrollment = (person) =>
    isStudent(person) && !person?.face_enrolled;
