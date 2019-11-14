package ch.psi.athos;

/**
 *
 */
public enum CameraType {
    Laser,
    Electrons,
    Photonics,
    Unknown;
    
    public static CameraType getType(String name) {
        if (name == null) {
            return null;
        }
        for (String s : new String[]{"LCAM"}) {
            if (name.contains(s)) {
                return Laser;
            }
        }
        for (String s : new String[]{"DSCR", "DSRM", "DLAC"}) {
            if (name.contains(s)) {
                return Electrons;
            }
        }
        for (String s : new String[]{"PROF", "PPRM", "PSSS", "PSCR", "PSRD"}) {
            if (name.contains(s)) {
                return Photonics;
            }
        }
        return Unknown;
    }
}
