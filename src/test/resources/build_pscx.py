# Copyright (c) 2026, SuperGrid Institute
# All rights reserved. Proprietary and confidential.
#
# Loads a .pscx in PSCAD and builds it, without running it: the reference a script's build is held to.
# Reports as the generated scripts do, in <name>.result beside the project: status, build_folder, errors.
#
#     python build_pscx.py <project.pscx> [--fortran "<compiler>"] [--version 5.1.0]
import argparse
import os
import sys

import mhi.pscad


def main():
    parser = argparse.ArgumentParser(description="Builds a PSCAD project file")
    parser.add_argument("pscx")
    parser.add_argument("--fortran")
    parser.add_argument("--version", default="5.1.0")
    args = parser.parse_args()
    path = os.path.abspath(args.pscx)
    name = os.path.splitext(os.path.basename(path))[0]
    status, build_folder, errors = "failed", "", []
    pscad = mhi.pscad.launch(minimize=True, splash=False, version=args.version)
    try:
        if args.fortran:
            pscad.settings(fortran_version=args.fortran)
        pscad.load(path)
        project = pscad.project(name)
        project.build()
        build_folder = project.temp_folder
        errors = [m.text for m in project.messages() if str(m.status).lower() == "error"]
        status = "build_failed" if errors else "built"
    except Exception as exception:
        errors.append(repr(exception))
    finally:
        with open(os.path.join(os.path.dirname(path), name + ".result"), "w", encoding="utf-8") as result:
            result.write(f"status={status}\n")
            result.write(f"build_folder={build_folder}\n")
            for error in errors:
                result.write("error=" + " ".join(str(error).split()) + "\n")
        pscad.quit()
    return 0


if __name__ == "__main__":
    sys.exit(main())
