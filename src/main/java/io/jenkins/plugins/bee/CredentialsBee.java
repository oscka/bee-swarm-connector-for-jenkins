package io.jenkins.plugins.bee;

public class CredentialsBee {
    private String id;
    private String userName;
    private String description;
    private String displayName;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String userName, String description) {
        StringBuffer dispTmp = new StringBuffer();
        if(userName.length() > 0){
            dispTmp.append(userName);
        }
        if(description.length() > 0){
            dispTmp.append("(");
            dispTmp.append(description);
            dispTmp.append(")");
        }
        this.displayName = dispTmp.toString();
    }

    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getUserName() {
        return userName;
    }
    public void setUserName(String userName) {
        this.userName = userName;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
}
