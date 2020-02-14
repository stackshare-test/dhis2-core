package org.hisp.dhis.tracker.validation.hooks;

/*
 * Copyright (c) 2004-2020, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import org.hisp.dhis.common.IllegalQueryException;
import org.hisp.dhis.common.OrganisationUnitSelectionMode;
import org.hisp.dhis.organisationunit.FeatureType;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramInstance;
import org.hisp.dhis.program.ProgramInstanceQueryParams;
import org.hisp.dhis.trackedentity.TrackedEntityInstance;
import org.hisp.dhis.trackedentitycomment.TrackedEntityComment;
import org.hisp.dhis.tracker.bundle.TrackerBundle;
import org.hisp.dhis.tracker.domain.Coordinate;
import org.hisp.dhis.tracker.domain.Enrollment;
import org.hisp.dhis.tracker.domain.EnrollmentStatus;
import org.hisp.dhis.tracker.domain.Note;
import org.hisp.dhis.tracker.preheat.PreheatHelper;
import org.hisp.dhis.tracker.report.TrackerErrorCode;
import org.hisp.dhis.tracker.report.TrackerErrorReport;
import org.hisp.dhis.tracker.report.ValidationErrorReporter;
import org.hisp.dhis.user.User;
import org.hisp.dhis.util.DateUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hisp.dhis.tracker.report.ValidationErrorReporter.newReport;

/**
 * @author Morten Svanæs <msvanaes@dhis2.org>
 */
@Component
public class EnrollmentExistingEnrollmentsValidationHook
    extends AbstractTrackerValidationHook
{

    @Override
    public int getOrder()
    {
        return 103;
    }

    @Override
    public List<TrackerErrorReport> validate( TrackerBundle bundle )
    {
        if ( bundle.getImportStrategy().isDelete() )
        {
            return Collections.emptyList();
        }

        ValidationErrorReporter reporter = new ValidationErrorReporter( bundle, this.getClass() );

        User actingUser = bundle.getPreheat().getUser();

        for ( Enrollment enrollment : bundle.getEnrollments() )
        {
            reporter.increment( enrollment );

            Program program = PreheatHelper.getProgram( bundle, enrollment.getProgram() );
            OrganisationUnit organisationUnit = PreheatHelper.getOrganisationUnit( bundle, enrollment.getOrgUnit() );
            TrackedEntityInstance trackedEntityInstance = PreheatHelper
                .getTrackedEntityInstance( bundle, enrollment.getTrackedEntityInstance() );

            // NOTE: maybe this should qualify as a hard break, on the prev hook (required properties).
            if ( program == null || organisationUnit == null || trackedEntityInstance == null )
            {
                continue;
            }

            if ( EnrollmentStatus.CANCELLED != enrollment.getStatus() )
            {
                // Enrollment(¶4.b.i) - When an enrollment with status != CANCELLED is being imported,
                // Check to make sure we don’t have any existing active enrollments for TEI + Program combination

                ProgramInstanceQueryParams params = new ProgramInstanceQueryParams();
                params.setOrganisationUnitMode( OrganisationUnitSelectionMode.ALL );
                params.setSkipPaging( true );
                params.setProgram( program );
                params.setTrackedEntityInstance( trackedEntityInstance );

                List<ProgramInstance> programInstances = programInstanceService.getProgramInstances( params );

                // Sort out only the programs the importing user has access too...
                // Stian, Morten H.  NOTE: How will this affect validation? If there is a conflict here but importing user is not allowed to know,
                // should import still be possible?
                List<Enrollment> programEnrollments = filterEnrollmentsUserAsAccessTo( actingUser, programInstances );

                // Enrollment(¶4.b.ii) - The error of enrolling more than once is possible only if the imported enrollment
                // has a state other than CANCELLED...
                if ( Boolean.TRUE.equals( program.getOnlyEnrollOnce() ) )
                {
                    Set<Enrollment> activeOrCompletedEnrollments = programEnrollments.stream()
                        .filter( programEnrollment -> EnrollmentStatus.ACTIVE == programEnrollment.getStatus()
                            || EnrollmentStatus.COMPLETED == programEnrollment.getStatus() )
                        .collect( Collectors.toSet() );

                    if ( !activeOrCompletedEnrollments.isEmpty() )
                    {
                        reporter.addError( newReport( TrackerErrorCode.E1016 )
                            .addArg( trackedEntityInstance )
                            .addArg( program ) );
                    }
                }
                else if ( EnrollmentStatus.ACTIVE == enrollment.getStatus() )
                {
                    // Optimize: this check/filter can be done in fetch above?
                    Set<Enrollment> activeEnrollments = programEnrollments.stream()
                        .filter( programEnrollment -> EnrollmentStatus.ACTIVE == programEnrollment.getStatus() )
                        .collect( Collectors.toSet() );

                    if ( !activeEnrollments.isEmpty() )
                    {
                        //Error: TrackedEntityInstance already has an active enrollment in another program...
                        reporter.addError( newReport( TrackerErrorCode.E1015 )
                            .addArg( trackedEntityInstance )
                            .addArg( program ) );
                    }
                }
            }

        }

        return reporter.getReportList();
    }

    public List<Enrollment> filterEnrollmentsUserAsAccessTo( User actingUser,
        Iterable<ProgramInstance> programInstances )
    {
        List<Enrollment> enrollments = new ArrayList<>();

        for ( ProgramInstance programInstance : programInstances )
        {
            if ( programInstance != null && trackerOwnershipManager.hasAccess( actingUser, programInstance ) )
            {
                List<String> errors = trackerAccessManager.canRead( actingUser, programInstance, true );
                if ( errors.isEmpty() )
                {
                    enrollments.add( getEnrollmentFromProgramInstance( programInstance ) );
                }
                else
                {
                    /// ??? // what shall we do here?, this exception seems out of place...
                    throw new IllegalQueryException( errors.toString() );
                }
            }
        }

        return enrollments;
    }

    public Enrollment getEnrollmentFromProgramInstance( ProgramInstance programInstance )
    {
        Enrollment enrollment = new Enrollment();
        enrollment.setEnrollment( programInstance.getUid() );

        if ( programInstance.getEntityInstance() != null )
        {
            enrollment.setTrackedEntityType( programInstance.getEntityInstance().getTrackedEntityType().getUid() );
            enrollment.setTrackedEntityInstance( programInstance.getEntityInstance().getUid() );
        }

        if ( programInstance.getOrganisationUnit() != null )
        {
            enrollment.setOrgUnit( programInstance.getOrganisationUnit().getUid() );
            enrollment.setOrgUnitName( programInstance.getOrganisationUnit().getName() );
        }

        if ( programInstance.getGeometry() != null )
        {
            enrollment.setGeometry( programInstance.getGeometry() );

            if ( programInstance.getProgram().getFeatureType().equals( FeatureType.POINT ) )
            {
                com.vividsolutions.jts.geom.Coordinate co = programInstance.getGeometry().getCoordinate();
                enrollment.setCoordinate( new Coordinate( co.x, co.y ) );
            }
        }

        enrollment.setCreated( DateUtils.getIso8601NoTz( programInstance.getCreated() ) );
        enrollment.setCreatedAtClient( DateUtils.getIso8601NoTz( programInstance.getCreatedAtClient() ) );
        enrollment.setLastUpdated( DateUtils.getIso8601NoTz( programInstance.getLastUpdated() ) );
        enrollment.setLastUpdatedAtClient( DateUtils.getIso8601NoTz( programInstance.getLastUpdatedAtClient() ) );
        enrollment.setProgram( programInstance.getProgram().getUid() );
        enrollment.setStatus( EnrollmentStatus.fromProgramStatus( programInstance.getStatus() ) );
        enrollment.setEnrollmentDate( programInstance.getEnrollmentDate() );
        enrollment.setIncidentDate( programInstance.getIncidentDate() );
        enrollment.setFollowup( programInstance.getFollowup() );
        enrollment.setCompletedDate( programInstance.getEndDate() );
        enrollment.setCompletedBy( programInstance.getCompletedBy() );
        enrollment.setStoredBy( programInstance.getStoredBy() );
        enrollment.setDeleted( programInstance.isDeleted() );

        List<TrackedEntityComment> comments = programInstance.getComments();

        for ( TrackedEntityComment comment : comments )
        {
            Note note = new Note();

            note.setNote( comment.getUid() );
            note.setValue( comment.getCommentText() );
            note.setStoredBy( comment.getCreator() );
            note.setStoredDate( DateUtils.getIso8601NoTz( comment.getCreated() ) );

            enrollment.getNotes().add( note );
        }

        return enrollment;
    }

}