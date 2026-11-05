# Conventions

## Copyright / author header

This project is SuperGrid Institute's, proprietary. New source files (Java, XML, Python, etc.) must use this header:

```
Copyright (c) <year>, SuperGrid Institute
All rights reserved. Proprietary and confidential.
```

And for Javadoc `@author` tags:

```
@author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
```

## Relationship with powsybl-simu-emt

This is an overlay on powsybl-simu-emt (MPL-2.0, https://github.com/JB-H/powsybl-simu-EMT), used as a library.
Never copy its code here: what is missing upstream is proposed there as a pull request (with upstream's own header),
and used here once released or installed locally (`mvn install` in the upstream checkout).

## Tests

`mvn test` runs what needs no PSCAD. `mvn test -Ppscad` also runs the tests tagged `pscad`, which drive the real
PSCAD 5.1 Professional through `mhi.pscad` (licence, Intel Fortran and the Windows SDK required).
