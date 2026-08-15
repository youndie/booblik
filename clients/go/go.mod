// The module path is its location in the repository — that is how Go identifies a module, so a
// client living in a monorepo subdirectory is imported by its full path and tagged `clients/go/vX.Y.Z`
// rather than `vX.Y.Z`. Go's convention for submodules, not a workaround; see clients/README.md.
//
// The package inside is `booblik`, not `go`: `go` is a keyword, and a package name that differs
// from its directory is ordinary (gopkg.in/yaml.v3 is `yaml`).
module github.com/youndie/booblik/clients/go

go 1.24
