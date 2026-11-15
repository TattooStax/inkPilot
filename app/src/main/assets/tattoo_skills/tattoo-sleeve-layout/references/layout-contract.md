# Portable layout record
Use JSON in existing project artifact storage, never a parallel CRM.
Fields: schema_version=1; units=mm; side=left/right; coverage; seam_location; axilla_excluded=true; stations=[{name,y_mm,circumference_mm,posture}]; shoulder={fit_method,attachment_arc_mm,cap_height_mm}; layers={art,shade,guides}; checks={registered_master,guide_free_transfer,seam_review,paper_wrap,body_fit,artist_signoff}; print={dpi,scale_percent,calibration_mm,mirror_verified}.
Station heights increase from proximal to distal. Include elbow, wrist and at least five stations. Missing measurements stay null; never fabricate them. Each size needs a separate profile.
States: concept; measured draft; placement checked; production approved. Record completeness cannot certify anatomy or artist judgement.
