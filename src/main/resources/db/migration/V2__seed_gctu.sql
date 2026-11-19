-- Initial academic configuration for the GCTU Diploma in Computer Science, Level 100 pilot.
-- Source: the supplied class timetable (First Semester 2026/2027).
-- NO student personal data is seeded here: the official register must be imported by an administrator.
DO $$
DECLARE
    v_inst UUID; v_dept UUID; v_prog UUID; v_year UUID; v_sem UUID; v_group UUID;
    v_d4a UUID; v_d4b UUID; v_d3a UUID; v_d3b UUID;
    v_course UUID; v_user UUID; v_lect UUID; v_tt UUID;
BEGIN
    INSERT INTO institutions(name, short_name, timezone)
        VALUES ('Ghana Communication Technology University', 'GCTU', 'Africa/Accra') RETURNING id INTO v_inst;
    INSERT INTO departments(institution_id, name) VALUES (v_inst, 'Computer Science') RETURNING id INTO v_dept;
    INSERT INTO programmes(department_id, name, award)
        VALUES (v_dept, 'Diploma in Computer Science', 'Diploma') RETURNING id INTO v_prog;
    INSERT INTO academic_years(institution_id, label) VALUES (v_inst, '2026/2027') RETURNING id INTO v_year;
    -- start_date is intentionally NULL: an administrator must confirm the date teaching week 1 begins.
    INSERT INTO semesters(academic_year_id, name, start_date, teaching_weeks, is_current)
        VALUES (v_year, 'First Semester', NULL, 13, true) RETURNING id INTO v_sem;

    INSERT INTO class_groups(programme_id, semester_id, level, name, expected_size, expected_size_source)
        VALUES (v_prog, v_sem, 100, 'Group A', 129,
                'Class timetable states 129 students. Must be reconciled with the imported register before activation.')
        RETURNING id INTO v_group;

    INSERT INTO venues(institution_id, code, description) VALUES (v_inst, 'D4A', 'Lecture room D4A') RETURNING id INTO v_d4a;
    INSERT INTO venues(institution_id, code, description) VALUES (v_inst, 'D4B', 'Lecture room D4B') RETURNING id INTO v_d4b;
    INSERT INTO venues(institution_id, code, description) VALUES (v_inst, 'D3A', 'Lecture room D3A') RETURNING id INTO v_d3a;
    INSERT INTO venues(institution_id, code, description) VALUES (v_inst, 'D3B', 'Lecture room D3B') RETURNING id INTO v_d3b;

    -- Helper pattern: lecturer accounts are created without credentials; an administrator issues
    -- sign-in credentials from the Lecturers page.

    -- Monday: Digital Electronics
    INSERT INTO courses(programme_id, code, title) VALUES (v_prog, 'CSNS 141', 'Digital Electronics') RETURNING id INTO v_course;
    INSERT INTO app_users(role, status) VALUES ('LECTURER', 'ACTIVE') RETURNING id INTO v_user;
    INSERT INTO lecturers(user_id, title, full_name, department_id) VALUES (v_user, NULL, 'Gabriel Gidisu', v_dept) RETURNING id INTO v_lect;
    INSERT INTO course_assignments(course_id, lecturer_id, semester_id) VALUES (v_course, v_lect, v_sem);
    INSERT INTO class_timetables(course_id, class_group_id, semester_id, day_of_week, start_time, end_time, venue_id, mode)
        VALUES (v_course, v_group, v_sem, 1, '13:00', '15:00', v_d4a, 'IN_PERSON');

    -- Tuesday: Introduction to Computer Systems
    INSERT INTO courses(programme_id, code, alt_code, title) VALUES (v_prog, 'CIIS 115', 'CSSD 111', 'Introduction to Computer Systems') RETURNING id INTO v_course;
    INSERT INTO app_users(role, status) VALUES ('LECTURER', 'ACTIVE') RETURNING id INTO v_user;
    INSERT INTO lecturers(user_id, title, full_name, department_id) VALUES (v_user, 'Dr.', 'Amanda Quist-Okronipa', v_dept) RETURNING id INTO v_lect;
    INSERT INTO course_assignments(course_id, lecturer_id, semester_id) VALUES (v_course, v_lect, v_sem);
    INSERT INTO class_timetables(course_id, class_group_id, semester_id, day_of_week, start_time, end_time, venue_id, mode)
        VALUES (v_course, v_group, v_sem, 2, '11:00', '13:00', v_d3b, 'IN_PERSON');

    -- Wednesday morning: Programming & Problem-Solving
    INSERT INTO courses(programme_id, code, title) VALUES (v_prog, 'CSSD 101', 'Programming & Problem-Solving') RETURNING id INTO v_course;
    INSERT INTO app_users(role, status) VALUES ('LECTURER', 'ACTIVE') RETURNING id INTO v_user;
    INSERT INTO lecturers(user_id, title, full_name, department_id) VALUES (v_user, NULL, 'Rebecca Adwoa Amponsah', v_dept) RETURNING id INTO v_lect;
    INSERT INTO course_assignments(course_id, lecturer_id, semester_id) VALUES (v_course, v_lect, v_sem);
    INSERT INTO class_timetables(course_id, class_group_id, semester_id, day_of_week, start_time, end_time, venue_id, mode)
        VALUES (v_course, v_group, v_sem, 3, '09:00', '11:00', v_d4a, 'IN_PERSON');

    -- Wednesday afternoon: Linear Algebra (D3A; D4B in teaching weeks 2, 6 and 10)
    INSERT INTO courses(programme_id, code, title) VALUES (v_prog, 'MATH 105', 'Linear Algebra') RETURNING id INTO v_course;
    INSERT INTO app_users(role, status) VALUES ('LECTURER', 'ACTIVE') RETURNING id INTO v_user;
    INSERT INTO lecturers(user_id, title, full_name, department_id) VALUES (v_user, 'Dr.', 'Stephen Kwaku Okrah', v_dept) RETURNING id INTO v_lect;
    INSERT INTO course_assignments(course_id, lecturer_id, semester_id) VALUES (v_course, v_lect, v_sem);
    INSERT INTO class_timetables(course_id, class_group_id, semester_id, day_of_week, start_time, end_time, venue_id, mode)
        VALUES (v_course, v_group, v_sem, 3, '13:00', '15:00', v_d3a, 'IN_PERSON') RETURNING id INTO v_tt;
    INSERT INTO timetable_exceptions(timetable_id, week_number, venue_id, note) VALUES
        (v_tt, 2, v_d4b, 'Venue changes to D4B'),
        (v_tt, 6, v_d4b, 'Venue changes to D4B'),
        (v_tt, 10, v_d4b, 'Venue changes to D4B');

    -- Thursday: General Communication Skills I (online, except campus weeks 4 and 8 in D4B).
    -- Course code was not stated on the timetable: left NULL until confirmed by an administrator.
    INSERT INTO courses(programme_id, code, title) VALUES (v_prog, NULL, 'General Communication Skills I') RETURNING id INTO v_course;
    INSERT INTO app_users(role, status) VALUES ('LECTURER', 'ACTIVE') RETURNING id INTO v_user;
    INSERT INTO lecturers(user_id, title, full_name, department_id) VALUES (v_user, 'Mr.', 'Isaac Darko Danquah', v_dept) RETURNING id INTO v_lect;
    INSERT INTO course_assignments(course_id, lecturer_id, semester_id) VALUES (v_course, v_lect, v_sem);
    INSERT INTO class_timetables(course_id, class_group_id, semester_id, day_of_week, start_time, end_time, venue_id, mode, notes)
        VALUES (v_course, v_group, v_sem, 4, '13:00', '15:00', NULL, 'ONLINE', 'Online except campus weeks 4 and 8') RETURNING id INTO v_tt;
    INSERT INTO timetable_exceptions(timetable_id, week_number, venue_id, mode, note) VALUES
        (v_tt, 4, v_d4b, 'IN_PERSON', 'Campus week: D4B'),
        (v_tt, 8, v_d4b, 'IN_PERSON', 'Campus week: D4B');

    -- Friday: Discrete Mathematics
    INSERT INTO courses(programme_id, code, title) VALUES (v_prog, 'MATH 103', 'Discrete Mathematics') RETURNING id INTO v_course;
    INSERT INTO app_users(role, status) VALUES ('LECTURER', 'ACTIVE') RETURNING id INTO v_user;
    INSERT INTO lecturers(user_id, title, full_name, department_id) VALUES (v_user, 'Dr.', 'Adusei-Poku Afful', v_dept) RETURNING id INTO v_lect;
    INSERT INTO course_assignments(course_id, lecturer_id, semester_id) VALUES (v_course, v_lect, v_sem);
    INSERT INTO class_timetables(course_id, class_group_id, semester_id, day_of_week, start_time, end_time, venue_id, mode)
        VALUES (v_course, v_group, v_sem, 5, '09:00', '11:00', v_d3a, 'IN_PERSON');
END $$;

-- Default policies (editable by administrators; every change is audited)
INSERT INTO system_settings(key, value) VALUES
    ('attendance.lateAfterMinutes', '15'),
    ('attendance.defaultDurationMinutes', '20'),
    ('attendance.otpRotationSeconds', '60'),
    ('attendance.lateCountsAsAttended', 'true'),
    ('attendance.excusedExcludedFromDenominator', 'true'),
    ('attendance.lowAttendanceThreshold', '75'),
    ('attendance.excellentAttendanceThreshold', '90'),
    ('attendance.maxOtpFailuresPerSession', '5'),
    ('security.faceOnLogin', 'true'),
    ('security.faceOnAttendance', 'true'),
    ('security.passkeyOnAttendance', 'true'),
    ('security.registrationRequiresApproval', 'true'),
    ('security.maxFaceFailuresPerHour', '6'),
    ('face.matchThreshold', '0.42'),
    ('face.duplicateCheckOnEnrol', 'true'),
    ('biometric.retentionDays', '730'),
    ('proximity.defaultPolicy', 'EVIDENCE');
