# Legacy invalid identity (#234)

Saving checks the effective stored-or-typed identity, including its checksum.
An unrelated edit cannot bypass an invalid legacy ID. Blank personal fields keep
the stored value; clearing the visible ID field therefore does not delete or fix
that ID. Enter a valid replacement before saving unrelated edits. This note does
not change passport mode, credential clearing, or any UI wording. The merge
regression uses only synthetic invalid and valid identity fixtures.
