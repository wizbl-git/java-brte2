@echo off
IF exist .\out\ (
echo Delete folder out, output-directory
rmdir .\\out .\\output-directory /s /q
) else (
echo Not exist folder.
)
